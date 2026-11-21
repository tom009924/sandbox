package com.example.todo.infrastructure.grpc

import com.example.todo.application.AdminStatsService
import com.example.todo.application.PatchTodoCommand
import com.example.todo.application.PatchTodoSubtaskCommand
import com.example.todo.application.TodoCommand
import com.example.todo.application.TodoCommentInfo
import com.example.todo.application.TodoCommentService
import com.example.todo.application.TodoListQuery
import com.example.todo.application.TodoService
import com.example.todo.application.TodoSubtaskService
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.TodoCommentForbiddenException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.AdminStats
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.UpdateTodoRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority

class TodoGrpcServiceTest {
    private val todoService: TodoService = mock(TodoService::class.java)
    private val todoSubtaskService: TodoSubtaskService = mock(TodoSubtaskService::class.java)
    private val todoCommentService: TodoCommentService = mock(TodoCommentService::class.java)
    private val adminStatsService: AdminStatsService = mock(AdminStatsService::class.java)

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val todoId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val subtaskId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val commentId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    @BeforeEach
    fun setUp() {
        val serverName = InProcessServerBuilder.generateName()
        val grpcService = TodoGrpcService(
            todoService = todoService,
            todoSubtaskService = todoSubtaskService,
            todoCommentService = todoCommentService,
            adminStatsService = adminStatsService,
        )
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    grpcService,
                    InternalApiKeyGrpcInterceptor(TEST_API_KEY),
                ),
            )
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `rejects request without internal api key`() {
        val unauthStub = TodoServiceGrpc.newBlockingStub(channel)
        val ex = assertThrows(StatusRuntimeException::class.java) {
            unauthStub.getAdminStats(GetAdminStatsRequest.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    @Test
    fun `handles todo create and read RPCs`() {
        val sample = sampleTodo()
        `when`(
            todoService.createIdempotent(
                userId,
                "key-1",
                TodoCommand(
                    title = "Write gRPC tests",
                    priority = TodoPriority.HIGH,
                    tags = listOf("grpc"),
                ),
            ),
        ).thenReturn(sample)
        `when`(todoService.list(userId, TodoListQuery())).thenReturn(listOf(sample))
        `when`(todoService.get(todoId, userId)).thenReturn(sample)

        val stub = authenticatedStub()
        val created = stub.createTodo(
            CreateTodoRequest.newBuilder()
                .setUserId(userId.toString())
                .setIdempotencyKey("key-1")
                .setTitle("Write gRPC tests")
                .setPriority(ProtoTodoPriority.HIGH)
                .addTags("grpc")
                .build(),
        )
        assertEquals(todoId.toString(), created.id)
        assertEquals(ProtoTodoPriority.HIGH, created.priority)

        val listed = stub.listTodos(ListTodosRequest.newBuilder().setUserId(userId.toString()).build())
        assertEquals(1, listed.todosCount)

        val fetched = stub.getTodo(
            GetTodoRequest.newBuilder().setId(todoId.toString()).setUserId(userId.toString()).build(),
        )
        assertEquals("2026-05-01", fetched.dueDate)
    }

    @Test
    fun `handles todo update patch and delete RPCs`() {
        val sample = sampleTodo()
        `when`(
            todoService.update(
                todoId,
                userId,
                TodoCommand(
                    title = "Write gRPC tests",
                    completed = true,
                    priority = TodoPriority.MEDIUM,
                ),
            ),
        ).thenReturn(sample.copy(completed = true))
        `when`(
            todoService.patch(
                todoId,
                userId,
                PatchTodoCommand(title = "Patched"),
            ),
        ).thenReturn(sample.copy(title = "Patched"))

        val stub = authenticatedStub()
        val updated = stub.updateTodo(
            UpdateTodoRequest.newBuilder()
                .setId(todoId.toString())
                .setUserId(userId.toString())
                .setTitle("Write gRPC tests")
                .setCompleted(true)
                .build(),
        )
        assertTrue(updated.completed)

        val patched = stub.patchTodo(
            PatchTodoRequest.newBuilder()
                .setId(todoId.toString())
                .setUserId(userId.toString())
                .setTitle("Patched")
                .build(),
        )
        assertEquals("Patched", patched.title)

        stub.deleteTodo(DeleteTodoRequest.newBuilder().setId(todoId.toString()).setUserId(userId.toString()).build())
        verify(todoService).delete(todoId, userId)
    }

    private fun sampleTodo() = Todo(
        id = todoId,
        userId = userId,
        title = "Write gRPC tests",
        completed = false,
        description = "Cover CRUD",
        dueDate = LocalDate.of(2026, 5, 1),
        priority = TodoPriority.HIGH,
        estimatedMinutes = 30,
        tags = listOf("grpc"),
    )

    @Test
    fun `handles subtask RPCs`() {
        val subtask = TodoSubtask(id = subtaskId, todoId = todoId, title = "Step 1", completed = false, position = 0)
        `when`(todoSubtaskService.add(todoId, userId, "Step 1")).thenReturn(subtask)
        `when`(todoSubtaskService.list(todoId, userId)).thenReturn(listOf(subtask))
        `when`(
            todoSubtaskService.patch(
                todoId,
                subtaskId,
                userId,
                PatchTodoSubtaskCommand(completed = true),
            ),
        ).thenReturn(subtask.copy(completed = true))

        val stub = authenticatedStub()
        assertEquals(
            "Step 1",
            stub.createSubtask(
                CreateSubtaskRequest.newBuilder()
                    .setTodoId(todoId.toString())
                    .setUserId(userId.toString())
                    .setTitle("Step 1")
                    .build(),
            ).title,
        )
        assertEquals(
            1,
            stub.listSubtasks(
                ListSubtasksRequest.newBuilder().setTodoId(todoId.toString()).setUserId(userId.toString()).build(),
            ).subtasksCount,
        )
        assertTrue(
            stub.patchSubtask(
                PatchSubtaskRequest.newBuilder()
                    .setTodoId(todoId.toString())
                    .setSubtaskId(subtaskId.toString())
                    .setUserId(userId.toString())
                    .setCompleted(true)
                    .build(),
            ).completed,
        )
        stub.deleteSubtask(
            DeleteSubtaskRequest.newBuilder()
                .setTodoId(todoId.toString())
                .setSubtaskId(subtaskId.toString())
                .setUserId(userId.toString())
                .build(),
        )
        verify(todoSubtaskService).delete(todoId, subtaskId, userId)
    }

    @Test
    fun `handles comment and admin stats RPCs`() {
        val comment = TodoCommentInfo(
            id = commentId,
            todoId = todoId,
            userId = userId,
            username = "alice",
            content = "Looks good",
            createdAt = Instant.parse("2026-05-01T10:00:00Z"),
        )
        `when`(todoCommentService.add(todoId, userId, "Looks good")).thenReturn(comment)
        `when`(todoCommentService.list(todoId, userId)).thenReturn(listOf(comment))
        `when`(adminStatsService.getStats()).thenReturn(AdminStats(10, 20, 5, 15))

        val stub = authenticatedStub()
        assertEquals(
            "alice",
            stub.createComment(
                CreateCommentRequest.newBuilder()
                    .setTodoId(todoId.toString())
                    .setUserId(userId.toString())
                    .setContent("Looks good")
                    .build(),
            ).username,
        )
        assertEquals(
            1,
            stub.listComments(
                ListCommentsRequest.newBuilder().setTodoId(todoId.toString()).setUserId(userId.toString()).build(),
            ).commentsCount,
        )
        stub.deleteComment(
            DeleteCommentRequest.newBuilder()
                .setTodoId(todoId.toString())
                .setCommentId(commentId.toString())
                .setUserId(userId.toString())
                .build(),
        )
        verify(todoCommentService).delete(todoId, commentId, userId)

        val stats = stub.getAdminStats(GetAdminStatsRequest.getDefaultInstance())
        assertEquals(10L, stats.totalUsers)
        assertEquals(15L, stats.activeTodos)
    }

    @Test
    fun `maps domain exceptions to gRPC status codes`() {
        `when`(todoService.get(todoId, userId)).thenThrow(TodoNotFoundException(todoId))
        `when`(todoService.createIdempotent(userId, "dup", TodoCommand(title = "Test")))
            .thenThrow(IdempotencyConflictException("dup"))
        `when`(todoCommentService.delete(todoId, commentId, userId))
            .thenThrow(TodoCommentForbiddenException("Forbidden"))

        val stub = authenticatedStub()
        val notFound = assertThrows(StatusRuntimeException::class.java) {
            stub.getTodo(GetTodoRequest.newBuilder().setId(todoId.toString()).setUserId(userId.toString()).build())
        }
        assertEquals(Status.Code.NOT_FOUND, notFound.status.code)

        val conflict = assertThrows(StatusRuntimeException::class.java) {
            stub.createTodo(
                CreateTodoRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setIdempotencyKey("dup")
                    .setTitle("Test")
                    .build(),
            )
        }
        assertEquals(Status.Code.ALREADY_EXISTS, conflict.status.code)

        val forbidden = assertThrows(StatusRuntimeException::class.java) {
            stub.deleteComment(
                DeleteCommentRequest.newBuilder()
                    .setTodoId(todoId.toString())
                    .setCommentId(commentId.toString())
                    .setUserId(userId.toString())
                    .build(),
            )
        }
        assertEquals(Status.Code.PERMISSION_DENIED, forbidden.status.code)
    }

    private fun authenticatedStub(): TodoServiceGrpc.TodoServiceBlockingStub {
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, TEST_API_KEY)
        }
        return TodoServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private companion object {
        const val TEST_API_KEY = "test-internal-key"
    }
}
