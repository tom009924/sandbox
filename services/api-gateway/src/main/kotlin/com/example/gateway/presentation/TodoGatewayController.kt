package com.example.gateway.presentation

import com.example.gateway.grpc.TodoGrpcClient
import com.example.gateway.presentation.api.TodoCommentsApi
import com.example.gateway.presentation.api.TodoSubtasksApi
import com.example.gateway.presentation.api.TodosApi
import com.example.gateway.presentation.model.CreateTodoCommentRequest
import com.example.gateway.presentation.model.CreateTodoRequest
import com.example.gateway.presentation.model.CreateTodoSubtaskRequest
import com.example.gateway.presentation.model.PatchTodoRequest
import com.example.gateway.presentation.model.PatchTodoSubtaskRequest
import com.example.gateway.presentation.model.SortOrder
import com.example.gateway.presentation.model.Todo
import com.example.gateway.presentation.model.TodoCommentResponse
import com.example.gateway.presentation.model.TodoPriority
import com.example.gateway.presentation.model.TodoSortField
import com.example.gateway.presentation.model.TodoSubtaskResponse
import com.example.gateway.presentation.model.UpdateTodoRequest
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoSubtaskMessage
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.grpc.v1.CreateTodoRequest as ProtoCreateTodoRequest
import com.example.todo.grpc.v1.PatchTodoRequest as ProtoPatchTodoRequest
import com.example.todo.grpc.v1.SortOrder as ProtoSortOrder
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority
import com.example.todo.grpc.v1.TodoSortField as ProtoTodoSortField
import com.example.todo.grpc.v1.UpdateTodoRequest as ProtoUpdateTodoRequest

@RestController
@Suppress("TooManyFunctions")
class TodoGatewayController(
    private val todoGrpcClient: TodoGrpcClient,
) : TodosApi, TodoSubtasksApi, TodoCommentsApi {

    @Suppress("LongParameterList")
    override fun listTodos(
        completed: Boolean?,
        priority: TodoPriority?,
        tag: String?,
        q: String?,
        dueBefore: LocalDate?,
        dueAfter: LocalDate?,
        sortBy: TodoSortField?,
        sortOrder: SortOrder?,
        projectId: UUID?,
        assigneeId: UUID?,
    ): ResponseEntity<List<Todo>> {
        val builder = ListTodosRequest.newBuilder().setUserId(currentUserId().toString())
        completed?.let { builder.setCompleted(it) }
        priority?.let { builder.setPriority(it.toTodoProto()) }
        tag?.let { builder.setTag(it) }
        q?.let { builder.setKeyword(it) }
        dueBefore?.let { builder.setDueBefore(it.toString()) }
        dueAfter?.let { builder.setDueAfter(it.toString()) }
        sortBy?.let { builder.setSortBy(it.toProto()) }
        sortOrder?.let { builder.setSortOrder(it.toProto()) }
        projectId?.let { builder.setProjectId(it.toString()) }
        assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.listTodos(builder.build()).map { it.toResponse() })
    }

    override fun getTodo(id: UUID): ResponseEntity<Todo> {
        val todo = todoGrpcClient.getTodo(
            GetTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(todo.toResponse())
    }

    override fun createTodo(
        idempotencyKey: String,
        createTodoRequest: CreateTodoRequest,
    ): ResponseEntity<Todo> {
        validateTagItems(createTodoRequest.tags)
        val builder = ProtoCreateTodoRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setIdempotencyKey(idempotencyKey)
            .setTitle(createTodoRequest.title)
            .addAllTags(createTodoRequest.tags.orEmpty())
        createTodoRequest.description?.let { builder.setDescription(it) }
        createTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        createTodoRequest.priority?.let { builder.setPriority(it.toTodoProto()) }
        createTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        createTodoRequest.projectId?.let { builder.setProjectId(it.toString()) }
        createTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        val created = todoGrpcClient.createTodo(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun updateTodo(
        id: UUID,
        updateTodoRequest: UpdateTodoRequest,
    ): ResponseEntity<Todo> {
        validateTagItems(updateTodoRequest.tags)
        val builder = ProtoUpdateTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
            .setTitle(updateTodoRequest.title)
            .setCompleted(updateTodoRequest.completed)
            .addAllTags(updateTodoRequest.tags.orEmpty())
        updateTodoRequest.description?.let { builder.setDescription(it) }
        updateTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        updateTodoRequest.priority?.let { builder.setPriority(it.toTodoProto()) }
        updateTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        updateTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.updateTodo(builder.build()).toResponse())
    }

    override fun patchTodo(
        id: UUID,
        patchTodoRequest: PatchTodoRequest,
    ): ResponseEntity<Todo> {
        validateTagItems(patchTodoRequest.tags)
        val builder = ProtoPatchTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
        patchTodoRequest.title?.let { builder.setTitle(it) }
        patchTodoRequest.completed?.let { builder.setCompleted(it) }
        patchTodoRequest.description?.let { builder.setDescription(it) }
        patchTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        patchTodoRequest.priority?.let { builder.setPriority(it.toTodoProto()) }
        patchTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        if (patchTodoRequest.tags != null) {
            builder.setHasTags(true).addAllTags(patchTodoRequest.tags)
        }
        patchTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.patchTodo(builder.build()).toResponse())
    }

    override fun deleteTodo(id: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteTodo(
            DeleteTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listTodoSubtasks(id: UUID): ResponseEntity<List<TodoSubtaskResponse>> {
        val subtasks = todoGrpcClient.listSubtasks(
            ListSubtasksRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(subtasks.map { it.toResponse() })
    }

    override fun createTodoSubtask(
        id: UUID,
        createTodoSubtaskRequest: CreateTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val created = todoGrpcClient.createSubtask(
            CreateSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .setTitle(createTodoSubtaskRequest.title)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun patchTodoSubtask(
        id: UUID,
        subtaskId: UUID,
        patchTodoSubtaskRequest: PatchTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val builder = PatchSubtaskRequest.newBuilder()
            .setTodoId(id.toString())
            .setSubtaskId(subtaskId.toString())
            .setUserId(currentUserId().toString())
        patchTodoSubtaskRequest.title?.let { builder.setTitle(it) }
        patchTodoSubtaskRequest.completed?.let { builder.setCompleted(it) }
        patchTodoSubtaskRequest.position?.let { builder.setPosition(it) }
        return ResponseEntity.ok(todoGrpcClient.patchSubtask(builder.build()).toResponse())
    }

    override fun deleteTodoSubtask(id: UUID, subtaskId: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteSubtask(
            DeleteSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setSubtaskId(subtaskId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listTodoComments(id: UUID): ResponseEntity<List<TodoCommentResponse>> {
        val comments = todoGrpcClient.listComments(
            ListCommentsRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(comments.map { it.toResponse() })
    }

    override fun createTodoComment(
        id: UUID,
        createTodoCommentRequest: CreateTodoCommentRequest,
    ): ResponseEntity<TodoCommentResponse> {
        val created = todoGrpcClient.createComment(
            CreateCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .setContent(createTodoCommentRequest.content)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun deleteTodoComment(id: UUID, commentId: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteComment(
            DeleteCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setCommentId(commentId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }
}

private fun TodoMessage.toResponse() = Todo(
    id = UUID.fromString(id),
    userId = UUID.fromString(userId),
    title = title,
    completed = completed,
    description = if (hasDescription()) description else null,
    dueDate = if (hasDueDate()) LocalDate.parse(dueDate) else null,
    priority = priority.toApi(),
    estimatedMinutes = if (hasEstimatedMinutes()) estimatedMinutes else null,
    tags = tagsList,
    projectId = if (hasProjectId()) UUID.fromString(projectId) else null,
    assigneeId = if (hasAssigneeId()) UUID.fromString(assigneeId) else null,
)

private fun TodoSubtaskMessage.toResponse() = TodoSubtaskResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    title = title,
    completed = completed,
    position = position,
)

private fun TodoCommentMessage.toResponse() = TodoCommentResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    userId = UUID.fromString(userId),
    username = username,
    content = content,
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

private fun TodoPriority.toTodoProto(): ProtoTodoPriority = when (this) {
    TodoPriority.LOW -> ProtoTodoPriority.LOW
    TodoPriority.MEDIUM -> ProtoTodoPriority.MEDIUM
    TodoPriority.HIGH -> ProtoTodoPriority.HIGH
}

private fun ProtoTodoPriority.toApi(): TodoPriority = when (this) {
    ProtoTodoPriority.LOW -> TodoPriority.LOW
    ProtoTodoPriority.MEDIUM,
    ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
    ProtoTodoPriority.UNRECOGNIZED,
    -> TodoPriority.MEDIUM
    ProtoTodoPriority.HIGH -> TodoPriority.HIGH
}

private fun TodoSortField.toProto(): ProtoTodoSortField = when (this) {
    TodoSortField.DUE_DATE -> ProtoTodoSortField.DUE_DATE
    TodoSortField.PRIORITY -> ProtoTodoSortField.PRIORITY
    TodoSortField.TITLE -> ProtoTodoSortField.TITLE
}

private fun SortOrder.toProto(): ProtoSortOrder = when (this) {
    SortOrder.ASC -> ProtoSortOrder.ASC
    SortOrder.DESC -> ProtoSortOrder.DESC
}
