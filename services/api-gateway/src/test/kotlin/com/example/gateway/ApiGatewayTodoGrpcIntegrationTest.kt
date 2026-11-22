package com.example.gateway

import com.example.gateway.grpc.TodoGrpcClient
import com.example.gateway.security.AdminApiKeyFilter
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.AdminStatsMessage
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.EmptyResponse
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListCommentsResponse
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectMembersResponse
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ListProjectsResponse
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListSubtasksResponse
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.ListTodosResponse
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.ProjectRole
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoPriority
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.grpc.v1.UpdateProjectRequest
import com.example.todo.grpc.v1.UpdateTodoRequest
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiGatewayTodoGrpcIntegrationTest.InProcessTodoGrpcConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiGatewayTodoGrpcIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val todoGrpcServer: Server,
) {

    @AfterAll
    fun tearDown() {
        todoGrpcServer.shutdownNow()
    }

    @Test
    fun `REST gateway calls todo-service via gRPC`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .header("Idempotency-Key", "gw-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Gateway gRPC todo","priority":"HIGH"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(FAKE_TODO_ID.toString()))
            .andExpect(jsonPath("$.priority").value("HIGH"))

        mockMvc.perform(
            get("/api/todos")
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Gateway gRPC todo"))

        mockMvc.perform(
            patch("/api/todos/$FAKE_TODO_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"completed":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.completed").value(true))

        mockMvc.perform(
            post("/api/todos/$FAKE_TODO_ID/subtasks")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Sub 1"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Sub 1"))

        mockMvc.perform(
            post("/api/todos/$FAKE_TODO_ID/comments")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"Nice work"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.username").value("alice"))

        mockMvc.perform(
            delete("/api/todos/$FAKE_TODO_ID")
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `rejects null tag items with 400 and handles unusual query strings on listTodos`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .header("Idempotency-Key", "gw-null-tag-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Null tag todo","tags":[null]}"""),
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            get(
                java.net.URI.create(
                    "/api/todos?%C3%A0jI%C3%BB%C3%BA%18%C2%A5%C3%BD=null&" +
                        "assigneeId=102a762d-6858-1ca8-8d86-22a9f475541f&" +
                        "q=%C3%BB%F0%AF%9C%AF&dueAfter=2544-07-28&" +
                        "projectId=d61a5eb9-2c9c-1d93-bd67-0811c6a6718f&" +
                        "sortBy=PRIORITY&tag=%E4%B9%82%C2%99%2B&dueBefore=0376-10-11&" +
                        "completed=false&priority=LOW&sortOrder=ASC",
                ),
            )
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `REST gateway calls project-service and admin stats via gRPC`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(
            post("/api/projects")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Core"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Core"))

        mockMvc.perform(
            get("/api/projects/$FAKE_PROJECT_ID/members")
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].username").value("alice"))

        mockMvc.perform(
            get("/api/admin/stats")
                .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "admin-secret-key"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalUsers").value(3))
            .andExpect(jsonPath("$.activeTodos").value(2))
    }

    @Test
    fun `maps gRPC NOT_FOUND to HTTP 404`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")
        mockMvc.perform(
            get("/api/todos/$MISSING_TODO_ID")
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("Todo not found"))
    }

    @Test
    fun `covers remaining todo subtask and comment endpoints`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(
            put("/api/todos/$FAKE_TODO_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Updated","completed":true,"priority":"LOW"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Updated"))

        mockMvc.perform(get("/api/todos/$FAKE_TODO_ID/subtasks").header("Authorization", "Bearer $jwt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Sub 1"))

        mockMvc.perform(
            patch("/api/todos/$FAKE_TODO_ID/subtasks/$FAKE_SUBTASK_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"completed":true,"position":1}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.completed").value(true))

        mockMvc.perform(
            delete("/api/todos/$FAKE_TODO_ID/subtasks/$FAKE_SUBTASK_ID")
                .header("Authorization", "Bearer $jwt"),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/todos/$FAKE_TODO_ID/comments").header("Authorization", "Bearer $jwt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].content").value("Nice work"))

        mockMvc.perform(
            delete("/api/todos/$FAKE_TODO_ID/comments/$FAKE_COMMENT_ID")
                .header("Authorization", "Bearer $jwt"),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `covers remaining project endpoints`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer $jwt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Core"))

        mockMvc.perform(get("/api/projects/$FAKE_PROJECT_ID").header("Authorization", "Bearer $jwt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Core"))

        mockMvc.perform(
            put("/api/projects/$FAKE_PROJECT_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Core v2","description":"Updated"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Core v2"))

        mockMvc.perform(
            post("/api/projects/$FAKE_PROJECT_ID/members")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","role":"MEMBER"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.username").value("bob"))

        mockMvc.perform(
            delete("/api/projects/$FAKE_PROJECT_ID/members/$FAKE_USER_ID")
                .header("Authorization", "Bearer $jwt"),
        ).andExpect(status().isNoContent)

        mockMvc.perform(delete("/api/projects/$FAKE_PROJECT_ID").header("Authorization", "Bearer $jwt"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `forwards optional fields and query filters for todos`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")

        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .header("Idempotency-Key", "gw-full-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title":"Full todo",
                      "description":"Details",
                      "dueDate":"2026-06-01",
                      "priority":"LOW",
                      "estimatedMinutes":45,
                      "tags":["work","urgent"],
                      "projectId":"$FAKE_PROJECT_ID",
                      "assigneeId":"$FAKE_USER_ID"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .param("completed", "false")
                .param("priority", "HIGH")
                .param("tag", "work")
                .param("q", "Full")
                .param("dueBefore", "2026-12-31")
                .param("dueAfter", "2026-01-01")
                .param("sortBy", "DUE_DATE")
                .param("sortOrder", "ASC")
                .param("projectId", FAKE_PROJECT_ID.toString())
                .param("assigneeId", FAKE_USER_ID.toString()),
        ).andExpect(status().isOk)

        mockMvc.perform(
            patch("/api/todos/$FAKE_TODO_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title":"Patched",
                      "completed":true,
                      "description":"New desc",
                      "dueDate":"2026-06-15",
                      "priority":"MEDIUM",
                      "estimatedMinutes":15,
                      "tags":["done"],
                      "assigneeId":"$FAKE_USER_ID"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isOk)
    }

    @Test
    fun `validates todo and project constraints against OpenAPI contract`() {
        val jwt = createJwt(FAKE_USER_ID, "alice")
        val tooLongTitle = "a".repeat(101)
        val tooLongKey = "k".repeat(256)

        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .header("Idempotency-Key", "valid-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$tooLongTitle"}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $jwt")
                .header("Idempotency-Key", tooLongKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Valid"}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            patch("/api/todos/$FAKE_TODO_ID")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"   "}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/projects")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Core","description":"bad\u0007control"}"""),
        ).andExpect(status().isBadRequest)
    }

    private fun createJwt(userId: UUID, username: String): String {
        val secret = "default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256"
        val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(key)
            .compact()
    }

    @TestConfiguration
    class InProcessTodoGrpcConfig {
        private val serverName = InProcessServerBuilder.generateName()

        @Bean
        fun todoGrpcServer(): Server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(FakeTodoGrpcService())
            .addService(FakeProjectGrpcService())
            .build()
            .start()

        @Bean
        @Primary
        fun inProcessTodoGrpcClient(todoGrpcServer: Server): TodoGrpcClient {
            check(todoGrpcServer.isShutdown.not())
            val channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build()
            return TodoGrpcClient(
                channel = channel,
                internalApiKey = "internal-secret-key",
                timeoutMillis = 5_000L,
            )
        }
    }

    private class FakeTodoGrpcService : TodoServiceGrpc.TodoServiceImplBase() {
        private val baseTodo = TodoMessage.newBuilder()
            .setId(FAKE_TODO_ID.toString())
            .setUserId(FAKE_USER_ID.toString())
            .setTitle("Gateway gRPC todo")
            .setCompleted(false)
            .setPriority(TodoPriority.HIGH)
            .build()

        override fun createTodo(request: CreateTodoRequest, responseObserver: StreamObserver<TodoMessage>) {
            responseObserver.onNext(baseTodo)
            responseObserver.onCompleted()
        }

        override fun listTodos(request: ListTodosRequest, responseObserver: StreamObserver<ListTodosResponse>) {
            responseObserver.onNext(ListTodosResponse.newBuilder().addTodos(baseTodo).build())
            responseObserver.onCompleted()
        }

        override fun getTodo(request: GetTodoRequest, responseObserver: StreamObserver<TodoMessage>) {
            if (request.id == MISSING_TODO_ID.toString()) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Todo not found").asRuntimeException())
                return
            }
            responseObserver.onNext(baseTodo)
            responseObserver.onCompleted()
        }

        override fun patchTodo(request: PatchTodoRequest, responseObserver: StreamObserver<TodoMessage>) {
            responseObserver.onNext(baseTodo.toBuilder().setCompleted(request.completed).build())
            responseObserver.onCompleted()
        }

        override fun updateTodo(request: UpdateTodoRequest, responseObserver: StreamObserver<TodoMessage>) {
            responseObserver.onNext(
                baseTodo.toBuilder().setTitle(request.title).setCompleted(request.completed).build(),
            )
            responseObserver.onCompleted()
        }

        override fun deleteTodo(request: DeleteTodoRequest, responseObserver: StreamObserver<EmptyResponse>) {
            responseObserver.onNext(EmptyResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun listSubtasks(
            request: ListSubtasksRequest,
            responseObserver: StreamObserver<ListSubtasksResponse>,
        ) {
            val sub = TodoSubtaskMessage.newBuilder()
                .setId(FAKE_SUBTASK_ID.toString())
                .setTodoId(request.todoId)
                .setTitle("Sub 1")
                .setCompleted(false)
                .setPosition(0)
                .build()
            responseObserver.onNext(ListSubtasksResponse.newBuilder().addSubtasks(sub).build())
            responseObserver.onCompleted()
        }

        override fun createSubtask(
            request: CreateSubtaskRequest,
            responseObserver: StreamObserver<TodoSubtaskMessage>,
        ) {
            responseObserver.onNext(
                TodoSubtaskMessage.newBuilder()
                    .setId(FAKE_SUBTASK_ID.toString())
                    .setTodoId(request.todoId)
                    .setTitle(request.title)
                    .setCompleted(false)
                    .setPosition(0)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun patchSubtask(
            request: PatchSubtaskRequest,
            responseObserver: StreamObserver<TodoSubtaskMessage>,
        ) {
            responseObserver.onNext(
                TodoSubtaskMessage.newBuilder()
                    .setId(request.subtaskId)
                    .setTodoId(request.todoId)
                    .setTitle("Sub 1")
                    .setCompleted(request.completed)
                    .setPosition(request.position)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun deleteSubtask(
            request: DeleteSubtaskRequest,
            responseObserver: StreamObserver<EmptyResponse>,
        ) {
            responseObserver.onNext(EmptyResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun listComments(
            request: ListCommentsRequest,
            responseObserver: StreamObserver<ListCommentsResponse>,
        ) {
            val comment = TodoCommentMessage.newBuilder()
                .setId(FAKE_COMMENT_ID.toString())
                .setTodoId(request.todoId)
                .setUserId(request.userId)
                .setUsername("alice")
                .setContent("Nice work")
                .setCreatedAt("2026-05-01T10:00:00Z")
                .build()
            responseObserver.onNext(ListCommentsResponse.newBuilder().addComments(comment).build())
            responseObserver.onCompleted()
        }

        override fun createComment(
            request: CreateCommentRequest,
            responseObserver: StreamObserver<TodoCommentMessage>,
        ) {
            responseObserver.onNext(
                TodoCommentMessage.newBuilder()
                    .setId(FAKE_COMMENT_ID.toString())
                    .setTodoId(request.todoId)
                    .setUserId(request.userId)
                    .setUsername("alice")
                    .setContent(request.content)
                    .setCreatedAt("2026-05-01T10:00:00Z")
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun deleteComment(
            request: DeleteCommentRequest,
            responseObserver: StreamObserver<EmptyResponse>,
        ) {
            responseObserver.onNext(EmptyResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun getAdminStats(
            request: GetAdminStatsRequest,
            responseObserver: StreamObserver<AdminStatsMessage>,
        ) {
            responseObserver.onNext(
                AdminStatsMessage.newBuilder()
                    .setTotalUsers(3)
                    .setTotalTodos(5)
                    .setCompletedTodos(3)
                    .setActiveTodos(2)
                    .build(),
            )
            responseObserver.onCompleted()
        }
    }

    private class FakeProjectGrpcService : ProjectServiceGrpc.ProjectServiceImplBase() {
        private val baseProject = ProjectMessage.newBuilder()
            .setId(FAKE_PROJECT_ID.toString())
            .setName("Core")
            .setCreatedBy(FAKE_USER_ID.toString())
            .setCreatedAt("2026-05-01T10:00:00Z")
            .build()

        override fun listProjects(
            request: ListProjectsRequest,
            responseObserver: StreamObserver<ListProjectsResponse>,
        ) {
            responseObserver.onNext(ListProjectsResponse.newBuilder().addProjects(baseProject).build())
            responseObserver.onCompleted()
        }

        override fun getProject(request: GetProjectRequest, responseObserver: StreamObserver<ProjectMessage>) {
            responseObserver.onNext(baseProject)
            responseObserver.onCompleted()
        }

        override fun createProject(request: CreateProjectRequest, responseObserver: StreamObserver<ProjectMessage>) {
            responseObserver.onNext(baseProject.toBuilder().setName(request.name).build())
            responseObserver.onCompleted()
        }

        override fun updateProject(request: UpdateProjectRequest, responseObserver: StreamObserver<ProjectMessage>) {
            responseObserver.onNext(baseProject.toBuilder().setName(request.name).build())
            responseObserver.onCompleted()
        }

        override fun deleteProject(request: DeleteProjectRequest, responseObserver: StreamObserver<EmptyResponse>) {
            responseObserver.onNext(EmptyResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun listProjectMembers(
            request: ListProjectMembersRequest,
            responseObserver: StreamObserver<ListProjectMembersResponse>,
        ) {
            responseObserver.onNext(
                ListProjectMembersResponse.newBuilder()
                    .addMembers(
                        ProjectMemberMessage.newBuilder()
                            .setProjectId(request.projectId)
                            .setUserId(FAKE_USER_ID.toString())
                            .setUsername("alice")
                            .setRole(ProjectRole.OWNER)
                            .setJoinedAt("2026-05-01T10:00:00Z")
                            .build(),
                    )
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun addProjectMember(
            request: AddProjectMemberRequest,
            responseObserver: StreamObserver<ProjectMemberMessage>,
        ) {
            responseObserver.onNext(
                ProjectMemberMessage.newBuilder()
                    .setProjectId(request.projectId)
                    .setUserId(FAKE_USER_ID.toString())
                    .setUsername(request.username)
                    .setRole(request.role)
                    .setJoinedAt("2026-05-01T10:00:00Z")
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun removeProjectMember(
            request: RemoveProjectMemberRequest,
            responseObserver: StreamObserver<EmptyResponse>,
        ) {
            responseObserver.onNext(EmptyResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }
    }

    private companion object {
        val FAKE_USER_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val FAKE_TODO_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val MISSING_TODO_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val FAKE_SUBTASK_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val FAKE_COMMENT_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val FAKE_PROJECT_ID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    }
}
