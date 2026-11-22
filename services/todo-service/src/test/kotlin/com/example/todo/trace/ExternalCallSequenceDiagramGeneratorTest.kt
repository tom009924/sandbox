package com.example.todo.trace

import com.example.todo.application.AdminStatsService
import com.example.todo.application.ProjectService
import com.example.todo.application.TodoAccessChecker
import com.example.todo.application.TodoCommentService
import com.example.todo.application.TodoService
import com.example.todo.application.TodoSubtaskService
import com.example.todo.application.TokenIssuer
import com.example.todo.application.TokenResult
import com.example.todo.domain.crypto.CryptoService
import com.example.todo.domain.crypto.TodoCryptoHandler
import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoComment
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.domain.model.User
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.notification.EmailNotification
import com.example.todo.domain.notification.EmailNotificationSender
import com.example.todo.domain.notification.EmailSendResult
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.ProjectRepository
import com.example.todo.domain.repository.TodoCommentRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.TodoSubtaskRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.domain.security.PasswordEncoder
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.UpdateProjectRequest
import com.example.todo.grpc.v1.UpdateTodoRequest
import com.example.todo.infrastructure.grpc.ProjectGrpcService
import com.example.todo.infrastructure.grpc.TodoGrpcService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority

class ExternalCallSequenceDiagramGeneratorTest {

    @Test
    fun `trace all endpoints and generate external call sequence diagrams`() {
        val tracer = ExternalCallTracer()
        TracedGrpcHarness(tracer).use { harness ->
            val userId = harness.traceRegister()
            val collaboratorId = harness.registerCollaborator("bob")
            harness.setCurrentUser(userId)
            harness.traceLogin()
            harness.traceUpdateSettings()
            harness.traceGetSettings()
            val todoId = harness.traceCreateTodo()
            harness.traceCreateTodoIdempotentReplay()
            harness.traceListTodos()
            harness.traceGetTodo(todoId)
            harness.traceUpdateTodo(todoId)
            harness.tracePatchTodoReopen(todoId)
            harness.traceAdminStats()
            harness.traceAdminUsers()
            harness.traceSubtaskLifecycle(todoId)
            harness.traceCommentLifecycle(todoId)
            harness.traceDeleteTodo(todoId)
            harness.traceProjectLifecycle(collaboratorId)
        }

        val traces = tracer.traces()
        val byEndpoint = traces.associateBy { it.endpoint }
        assertAllOpenApiEndpointsTraced(byEndpoint.keys)

        assertEquals(
            listOf(
                "Spanner:UserRepository.existsByUsername",
                "Spanner:UserRepository.save",
                "Email:POST /api/v1/emails",
            ),
            byEndpoint.getValue("POST /api/auth/register").events.map { "${it.system.alias}:${it.operation}" },
        )

        assertEquals(
            listOf(
                "Spanner:IdempotencyRecordRepository.findByUserIdAndKey",
                "Spanner:UserSettingsRepository.findByUserId",
                "Spanner:TodoRepository.countByUserIdAndCompletedFalse",
                "KMS:Encrypt",
                "Spanner:TodoRepository.save",
                "KMS:Decrypt",
                "PubSub:publish(CREATED)",
                "Spanner:IdempotencyRecordRepository.save",
            ),
            byEndpoint.getValue("POST /api/todos").events.map { "${it.system.alias}:${it.operation}" },
        )

        val outputPath = Path.of(
            System.getProperty(
                "externalCallDiagramOut",
                "build/reports/external-call-sequence.md",
            ),
        )
        tracer.writeReport(outputPath)
        assertTrue(Files.exists(outputPath))
        val report = Files.readString(outputPath)
        assertTrue(report.contains("```mermaid"))
        assertTrue(report.contains("POST /api/todos"))
    }

    private fun assertAllOpenApiEndpointsTraced(tracedEndpoints: Set<String>) {
        val openApiPath = Path.of("docs/dist/openapi.admin.yaml")
        val spec = Yaml().load<Map<String, Any>>(Files.readString(openApiPath))

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as? Map<String, Map<String, Any>> ?: emptyMap()
        val httpMethods = setOf("get", "put", "post", "delete", "patch")
        val normalizedTraced = tracedEndpoints.map { it.substringBefore(" (") }.toSet()
        val missing = paths.flatMap { (path, item) ->
            item.keys
                .filter { it.lowercase() in httpMethods }
                .map { "${it.uppercase()} $path" }
        }.filter { it !in normalizedTraced }

        assertEquals(
            emptyList<String>(),
            missing,
            "OpenAPI endpoints missing from ExternalCallSequenceDiagramGeneratorTest: $missing",
        )
    }

    @Suppress("TooManyFunctions")
    private class TracedGrpcHarness(private val tracer: ExternalCallTracer) : AutoCloseable {
        private var activeUserId: UUID = UUID.randomUUID()

        private val todoRepo = tracer.wrap(
            TodoRepository::class.java,
            InMemoryTodoRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "TodoRepository.${method.name}" }

        private val idempotencyRepo = tracer.wrap(
            IdempotencyRecordRepository::class.java,
            InMemoryIdempotencyRecordRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "IdempotencyRecordRepository.${method.name}" }

        private val userRepo = tracer.wrap(
            UserRepository::class.java,
            InMemoryUserRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "UserRepository.${method.name}" }

        private val userSettingsRepo = tracer.wrap(
            UserSettingsRepository::class.java,
            InMemoryUserSettingsRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "UserSettingsRepository.${method.name}" }

        private val projectRepo = tracer.wrap(
            ProjectRepository::class.java,
            InMemoryProjectRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "ProjectRepository.${method.name}" }

        private val projectMemberRepo = tracer.wrap(
            ProjectMemberRepository::class.java,
            InMemoryProjectMemberRepository(),
            ExternalSystem.SPANNER,
        ) { method, _ -> "ProjectMemberRepository.${method.name}" }

        private val cryptoService = tracer.wrap(
            CryptoService::class.java,
            StubCryptoService(),
            ExternalSystem.KMS,
        ) { method, _ -> if (method.name == "encrypt") "Encrypt" else "Decrypt" }

        private val eventPublisher = tracer.wrap(
            TodoEventPublisher::class.java,
            object : TodoEventPublisher {
                override fun publish(event: TodoEvent) = Unit
            },
            ExternalSystem.PUBSUB,
            async = true,
        ) { _, args ->
            val event = args?.firstOrNull() as? TodoEvent
            if (event != null) "publish(${event.eventType})" else "publish"
        }

        private val emailSender = tracer.wrap(
            EmailNotificationSender::class.java,
            object : EmailNotificationSender {
                override fun send(notification: EmailNotification): EmailSendResult =
                    EmailSendResult("msg-1", "SENT")
            },
            ExternalSystem.EMAIL,
        ) { _, _ -> "POST /api/v1/emails" }

        private val passwordEncoder = object : PasswordEncoder {
            override fun encode(rawPassword: String): String = "hashed:$rawPassword"
            override fun matches(rawPassword: String, encodedPassword: String): Boolean =
                encodedPassword == "hashed:$rawPassword"
        }

        private val tokenIssuer = object : TokenIssuer {
            override fun issueToken(user: AuthenticatedUser): TokenResult =
                TokenResult("stub-jwt", "Bearer", EXPIRES_IN_SECONDS)
        }

        private val subtaskStorage = linkedMapOf<UUID, TodoSubtask>()
        private val todoSubtaskRepo = tracer.wrap(
            TodoSubtaskRepository::class.java,
            object : TodoSubtaskRepository {
                override fun save(subtask: TodoSubtask): TodoSubtask {
                    val saved = subtask.copy(id = subtask.id ?: UUID.randomUUID())
                    subtaskStorage[requireNotNull(saved.id)] = saved
                    return saved
                }
                override fun findAllByTodoIdOrderByPositionAsc(todoId: UUID): List<TodoSubtask> =
                    subtaskStorage.values.filter { it.todoId == todoId }
                override fun findById(id: UUID): TodoSubtask? = subtaskStorage[id]
                override fun deleteById(id: UUID) {
                    subtaskStorage.remove(id)
                }
                override fun deleteAllByTodoId(todoId: UUID) {
                    subtaskStorage.entries.removeIf { it.value.todoId == todoId }
                }
            },
            ExternalSystem.SPANNER,
        ) { method, _ -> "TodoSubtaskRepository.${method.name}" }

        private val commentStorage = linkedMapOf<UUID, TodoComment>()
        private val todoCommentRepo = tracer.wrap(
            TodoCommentRepository::class.java,
            object : TodoCommentRepository {
                override fun save(comment: TodoComment): TodoComment {
                    val saved = comment.copy(id = comment.id ?: UUID.randomUUID())
                    commentStorage[requireNotNull(saved.id)] = saved
                    return saved
                }
                override fun findAllByTodoIdOrderByCreatedAtAsc(todoId: UUID): List<TodoComment> =
                    commentStorage.values.filter { it.todoId == todoId }
                override fun findById(id: UUID): TodoComment? = commentStorage[id]
                override fun deleteById(id: UUID) {
                    commentStorage.remove(id)
                }
                override fun deleteAllByTodoId(todoId: UUID) {
                    commentStorage.entries.removeIf { it.value.todoId == todoId }
                }
            },
            ExternalSystem.SPANNER,
        ) { method, _ -> "TodoCommentRepository.${method.name}" }

        private val todoGrpcService = TodoGrpcService(
            todoService = TodoService(
                todoRepository = todoRepo,
                idempotencyRecordRepository = idempotencyRepo,
                todoEventPublisher = eventPublisher,
                userSettingsRepository = userSettingsRepo,
                cryptoHandler = TodoCryptoHandler(cryptoService),
                projectMemberRepository = projectMemberRepo,
            ),
            todoSubtaskService = TodoSubtaskService(
                todoAccessChecker = TodoAccessChecker(todoRepo, projectMemberRepo),
                todoSubtaskRepository = todoSubtaskRepo,
            ),
            todoCommentService = TodoCommentService(
                todoAccessChecker = TodoAccessChecker(todoRepo, projectMemberRepo),
                todoCommentRepository = todoCommentRepo,
                userRepository = userRepo,
            ),
            adminStatsService = AdminStatsService(userRepo, todoRepo),
        )

        private val projectGrpcService = ProjectGrpcService(
            projectService = ProjectService(
                projectRepository = projectRepo,
                projectMemberRepository = projectMemberRepo,
                userRepository = userRepo,
                todoRepository = todoRepo,
            ),
        )

        private val serverName = InProcessServerBuilder.generateName()
        private val server: Server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(todoGrpcService)
            .addService(projectGrpcService)
            .build()
            .start()
        private val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
        private val todoStub = TodoServiceGrpc.newBlockingStub(channel)
        private val projectStub = ProjectServiceGrpc.newBlockingStub(channel)

        override fun close() {
            channel.shutdownNow()
            server.shutdownNow()
        }

        fun setCurrentUser(userId: UUID) {
            activeUserId = userId
        }

        fun registerCollaborator(username: String): UUID =
            registerUserThroughIdentityFlow(username, "secret123")

        fun traceRegister(): UUID = tracer.trace("POST /api/auth/register", transactionalReadOnly = false) {
            registerUserThroughIdentityFlow("alice", "secret123")
        }

        fun traceLogin() = tracer.trace("POST /api/auth/login", transactionalReadOnly = true) {
            val user = requireNotNull(userRepo.findByUsername("alice"))
            check(passwordEncoder.matches("secret123", user.passwordHash))
            tokenIssuer.issueToken(AuthenticatedUser(requireNotNull(user.id), user.username))
        }

        fun traceUpdateSettings() = tracer.trace("PUT /api/user/settings", transactionalReadOnly = false) {
            userSettingsRepo.save(
                UserSettings(
                    userId = activeUserId,
                    defaultPriority = TodoPriority.HIGH,
                    defaultEstimatedMinutes = DEFAULT_ESTIMATED_MINUTES,
                    maxActiveTodos = MAX_ACTIVE_TODOS,
                ),
            )
        }

        fun traceGetSettings() = tracer.trace("GET /api/user/settings", transactionalReadOnly = true) {
            userSettingsRepo.findByUserId(activeUserId) ?: UserSettings.defaultFor(activeUserId)
        }

        fun traceCreateTodo(): UUID = tracer.trace("POST /api/todos", transactionalReadOnly = false) {
            val response = todoStub.createTodo(
                CreateTodoRequest.newBuilder()
                    .setUserId(activeUserId.toString())
                    .setIdempotencyKey("idem-key-1")
                    .setTitle("Write diagram")
                    .setDescription("Secret notes")
                    .setPriority(ProtoTodoPriority.HIGH)
                    .addTags("docs")
                    .build(),
            )
            UUID.fromString(response.id)
        }

        fun traceCreateTodoIdempotentReplay() = tracer.trace(
            "POST /api/todos (idempotent replay)",
            transactionalReadOnly = false,
        ) {
            todoStub.createTodo(
                CreateTodoRequest.newBuilder()
                    .setUserId(activeUserId.toString())
                    .setIdempotencyKey("idem-key-1")
                    .setTitle("Write diagram")
                    .setDescription("Secret notes")
                    .setPriority(ProtoTodoPriority.HIGH)
                    .addTags("docs")
                    .build(),
            )
        }

        fun traceListTodos() = tracer.trace("GET /api/todos", transactionalReadOnly = true) {
            todoStub.listTodos(
                ListTodosRequest.newBuilder()
                    .setUserId(activeUserId.toString())
                    .build(),
            )
        }

        fun traceGetTodo(id: UUID) = tracer.trace("GET /api/todos/{id}", transactionalReadOnly = true) {
            todoStub.getTodo(
                GetTodoRequest.newBuilder()
                    .setId(id.toString())
                    .setUserId(activeUserId.toString())
                    .build(),
            )
        }

        fun traceUpdateTodo(id: UUID) = tracer.trace("PUT /api/todos/{id}", transactionalReadOnly = false) {
            todoStub.updateTodo(
                UpdateTodoRequest.newBuilder()
                    .setId(id.toString())
                    .setUserId(activeUserId.toString())
                    .setTitle("Write diagram v2")
                    .setCompleted(true)
                    .setDescription("Updated secret notes")
                    .setPriority(ProtoTodoPriority.MEDIUM)
                    .addTags("docs")
                    .build(),
            )
        }

        fun tracePatchTodoReopen(id: UUID) = tracer.trace(
            "PATCH /api/todos/{id} (reopen)",
            transactionalReadOnly = false,
        ) {
            todoStub.patchTodo(
                PatchTodoRequest.newBuilder()
                    .setId(id.toString())
                    .setUserId(activeUserId.toString())
                    .setCompleted(false)
                    .setDescription("Reopened notes")
                    .build(),
            )
        }

        fun traceAdminStats() = tracer.trace("GET /api/admin/stats", transactionalReadOnly = true) {
            todoStub.getAdminStats(GetAdminStatsRequest.getDefaultInstance())
        }

        fun traceAdminUsers() = tracer.trace("GET /api/admin/users", transactionalReadOnly = true) {
            userRepo.findAll()
        }

        fun traceDeleteTodo(id: UUID) = tracer.trace("DELETE /api/todos/{id}", transactionalReadOnly = false) {
            todoStub.deleteTodo(
                DeleteTodoRequest.newBuilder()
                    .setId(id.toString())
                    .setUserId(activeUserId.toString())
                    .build(),
            )
        }

        fun traceSubtaskLifecycle(todoId: UUID) {
            val subtaskId = tracer.trace("POST /api/todos/{id}/subtasks", transactionalReadOnly = false) {
                UUID.fromString(
                    todoStub.createSubtask(
                        CreateSubtaskRequest.newBuilder()
                            .setTodoId(todoId.toString())
                            .setUserId(activeUserId.toString())
                            .setTitle("Step 1")
                            .build(),
                    ).id,
                )
            }
            tracer.trace("GET /api/todos/{id}/subtasks", transactionalReadOnly = true) {
                todoStub.listSubtasks(
                    ListSubtasksRequest.newBuilder()
                        .setTodoId(todoId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
            tracer.trace("PATCH /api/todos/{id}/subtasks/{subtaskId}", transactionalReadOnly = false) {
                todoStub.patchSubtask(
                    PatchSubtaskRequest.newBuilder()
                        .setTodoId(todoId.toString())
                        .setSubtaskId(subtaskId.toString())
                        .setUserId(activeUserId.toString())
                        .setCompleted(true)
                        .build(),
                )
            }
            tracer.trace("DELETE /api/todos/{id}/subtasks/{subtaskId}", transactionalReadOnly = false) {
                todoStub.deleteSubtask(
                    DeleteSubtaskRequest.newBuilder()
                        .setTodoId(todoId.toString())
                        .setSubtaskId(subtaskId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
        }

        fun traceCommentLifecycle(todoId: UUID) {
            val commentId = tracer.trace("POST /api/todos/{id}/comments", transactionalReadOnly = false) {
                UUID.fromString(
                    todoStub.createComment(
                        CreateCommentRequest.newBuilder()
                            .setTodoId(todoId.toString())
                            .setUserId(activeUserId.toString())
                            .setContent("Review notes")
                            .build(),
                    ).id,
                )
            }
            tracer.trace("GET /api/todos/{id}/comments", transactionalReadOnly = true) {
                todoStub.listComments(
                    ListCommentsRequest.newBuilder()
                        .setTodoId(todoId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
            tracer.trace("DELETE /api/todos/{id}/comments/{commentId}", transactionalReadOnly = false) {
                todoStub.deleteComment(
                    DeleteCommentRequest.newBuilder()
                        .setTodoId(todoId.toString())
                        .setCommentId(commentId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
        }

        fun traceProjectLifecycle(collaboratorId: UUID) {
            val projectId = tracer.trace("POST /api/projects", transactionalReadOnly = false) {
                UUID.fromString(
                    projectStub.createProject(
                        CreateProjectRequest.newBuilder()
                            .setUserId(activeUserId.toString())
                            .setName("Shared")
                            .setDescription("Team project")
                            .build(),
                    ).id,
                )
            }
            tracer.trace("GET /api/projects", transactionalReadOnly = true) {
                projectStub.listProjects(
                    ListProjectsRequest.newBuilder().setUserId(activeUserId.toString()).build(),
                )
            }
            tracer.trace("GET /api/projects/{projectId}", transactionalReadOnly = true) {
                projectStub.getProject(
                    GetProjectRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
            tracer.trace("PUT /api/projects/{projectId}", transactionalReadOnly = false) {
                projectStub.updateProject(
                    UpdateProjectRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(activeUserId.toString())
                        .setName("Shared v2")
                        .setDescription("Updated")
                        .build(),
                )
            }
            traceProjectMemberLifecycle(projectId, collaboratorId)
            tracer.trace("DELETE /api/projects/{projectId}", transactionalReadOnly = false) {
                projectStub.deleteProject(
                    DeleteProjectRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
        }

        private fun traceProjectMemberLifecycle(projectId: UUID, collaboratorId: UUID) {
            tracer.trace("POST /api/projects/{projectId}/members", transactionalReadOnly = false) {
                projectStub.addProjectMember(
                    AddProjectMemberRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setActorUserId(activeUserId.toString())
                        .setUsername("bob")
                        .setRole(ProtoProjectRole.MEMBER)
                        .build(),
                )
            }
            tracer.trace("GET /api/projects/{projectId}/members", transactionalReadOnly = true) {
                projectStub.listProjectMembers(
                    ListProjectMembersRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(activeUserId.toString())
                        .build(),
                )
            }
            tracer.trace("DELETE /api/projects/{projectId}/members/{userId}", transactionalReadOnly = false) {
                projectStub.removeProjectMember(
                    RemoveProjectMemberRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setActorUserId(activeUserId.toString())
                        .setTargetUserId(collaboratorId.toString())
                        .build(),
                )
            }
        }

        private fun registerUserThroughIdentityFlow(username: String, rawPassword: String): UUID {
            val trimmed = username.trim()
            check(!userRepo.existsByUsername(trimmed))
            val saved = userRepo.save(
                User(
                    username = trimmed,
                    passwordHash = passwordEncoder.encode(rawPassword),
                ),
            )
            emailSender.send(
                EmailNotification(
                    to = "$trimmed@example.com",
                    subject = "Welcome to Todo App",
                    content = "Welcome to Todo App, $trimmed!",
                ),
            )
            return requireNotNull(saved.id)
        }

        private companion object {
            const val EXPIRES_IN_SECONDS = 3600L
            const val DEFAULT_ESTIMATED_MINUTES = 30
            const val MAX_ACTIVE_TODOS = 10
        }
    }

    private class StubCryptoService : CryptoService {
        override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray = ciphertext.reversedArray()
    }

    private class InMemoryTodoRepository : TodoRepository {
        private val store = linkedMapOf<UUID, Todo>()

        override fun findAll(): List<Todo> = store.values.toList()

        override fun findAllByUserId(
            userId: UUID,
            completed: Boolean?,
            priority: TodoPriority?,
            tag: String?,
        ): List<Todo> = store.values.filter { todo ->
            todo.userId == userId &&
                (completed == null || todo.completed == completed) &&
                (priority == null || todo.priority == priority) &&
                (tag == null || tag in todo.tags)
        }

        override fun findAllByProjectId(
            projectId: UUID,
            completed: Boolean?,
            priority: TodoPriority?,
            tag: String?,
        ): List<Todo> = store.values.filter { todo ->
            todo.projectId == projectId &&
                (completed == null || todo.completed == completed) &&
                (priority == null || todo.priority == priority) &&
                (tag == null || tag in todo.tags)
        }

        override fun findById(id: UUID): Todo? = store[id]

        override fun findByIdAndUserId(id: UUID, userId: UUID): Todo? =
            store[id]?.takeIf { it.userId == userId }

        override fun countByUserIdAndCompletedFalse(userId: UUID): Long =
            store.values.count { it.userId == userId && !it.completed }.toLong()

        override fun count(): Long = store.size.toLong()

        override fun countByCompleted(completed: Boolean): Long =
            store.values.count { it.completed == completed }.toLong()

        override fun save(todo: Todo): Todo {
            val assignedId = todo.id ?: UUID.randomUUID()
            val saved = todo.copy(id = assignedId)
            store[assignedId] = saved
            return saved
        }

        override fun delete(todo: Todo) {
            todo.id?.let { store.remove(it) }
        }

        override fun deleteAllByProjectId(projectId: UUID) {
            store.entries.removeIf { it.value.projectId == projectId }
        }
    }

    private class InMemoryIdempotencyRecordRepository : IdempotencyRecordRepository {
        private val store = linkedMapOf<Pair<UUID, String>, IdempotencyRecord>()

        override fun findByUserIdAndKey(userId: UUID, key: String): IdempotencyRecord? =
            store[userId to key]

        override fun save(record: IdempotencyRecord) {
            store[record.userId to record.key] = record
        }

        override fun deleteExpiredBefore(instant: Instant) {
            store.entries.removeIf { it.value.expiresAt.isBefore(instant) }
        }
    }

    private class InMemoryUserRepository : UserRepository {
        private val store = linkedMapOf<UUID, User>()

        override fun findById(id: UUID): User? = store[id]
        override fun findAllByIdIn(ids: Collection<UUID>): List<User> =
            ids.mapNotNull { store[it] }
        override fun findAll(): List<User> = store.values.toList()
        override fun findByUsername(username: String): User? = store.values.firstOrNull { it.username == username }
        override fun existsByUsername(username: String): Boolean = store.values.any { it.username == username }
        override fun save(user: User): User {
            val assignedId = user.id ?: UUID.randomUUID()
            val saved = user.copy(id = assignedId)
            store[assignedId] = saved
            return saved
        }
        override fun count(): Long = store.size.toLong()
        override fun deleteAll() = store.clear()
    }

    private class InMemoryUserSettingsRepository : UserSettingsRepository {
        private val store = linkedMapOf<UUID, UserSettings>()

        override fun findByUserId(userId: UUID): UserSettings? = store[userId]
        override fun save(userSettings: UserSettings): UserSettings {
            store[userSettings.userId] = userSettings
            return userSettings
        }
    }

    private class InMemoryProjectRepository : ProjectRepository {
        private val store = linkedMapOf<UUID, Project>()

        override fun save(project: Project): Project {
            val assignedId = project.id ?: UUID.randomUUID()
            val saved = project.copy(id = assignedId)
            store[assignedId] = saved
            return saved
        }

        override fun findById(id: UUID): Project? = store[id]
        override fun findAllByIdIn(ids: Collection<UUID>): List<Project> = ids.mapNotNull { store[it] }
        override fun deleteById(id: UUID) {
            store.remove(id)
        }
    }

    private class InMemoryProjectMemberRepository : ProjectMemberRepository {
        private val store = linkedMapOf<Pair<UUID, UUID>, ProjectMember>()

        override fun save(member: ProjectMember): ProjectMember {
            store[member.projectId to member.userId] = member
            return member
        }

        override fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember? =
            store[projectId to userId]

        override fun findAllByProjectId(projectId: UUID): List<ProjectMember> =
            store.values.filter { it.projectId == projectId }

        override fun findAllByUserId(userId: UUID): List<ProjectMember> =
            store.values.filter { it.userId == userId }

        override fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole): Long =
            store.values.count { it.projectId == projectId && it.role == role }.toLong()

        override fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID) {
            store.remove(projectId to userId)
        }

        override fun deleteAllByProjectId(projectId: UUID) {
            store.entries.removeIf { it.key.first == projectId }
        }
    }
}
