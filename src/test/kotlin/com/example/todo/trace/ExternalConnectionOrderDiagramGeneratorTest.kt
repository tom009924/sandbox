package com.example.todo.trace

import com.example.todo.application.AdminStatsService
import com.example.todo.application.AdminUserService
import com.example.todo.application.AuthService
import com.example.todo.application.ProjectService
import com.example.todo.application.TodoService
import com.example.todo.application.TodoSubtaskService
import com.example.todo.application.TokenIssuer
import com.example.todo.application.TokenResult
import com.example.todo.application.UserContext
import com.example.todo.application.UserSettingsService
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
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.TodoSubtaskRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.domain.security.PasswordEncoder
import com.example.todo.presentation.AdminController
import com.example.todo.presentation.AuthController
import com.example.todo.presentation.ProjectController
import com.example.todo.presentation.TodoController
import com.example.todo.presentation.TodoSubtaskController
import com.example.todo.presentation.UserSettingsController
import com.example.todo.presentation.model.AddProjectMemberRequest
import com.example.todo.presentation.model.CreateProjectRequest
import com.example.todo.presentation.model.CreateTodoRequest
import com.example.todo.presentation.model.CreateTodoSubtaskRequest
import com.example.todo.presentation.model.LoginRequest
import com.example.todo.presentation.model.PatchTodoRequest
import com.example.todo.presentation.model.PatchTodoSubtaskRequest
import com.example.todo.presentation.model.RegisterRequest
import com.example.todo.presentation.model.UpdateProjectRequest
import com.example.todo.presentation.model.UpdateTodoRequest
import com.example.todo.presentation.model.UpdateUserSettingsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import com.example.todo.presentation.model.ProjectRole as ApiProjectRole
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority

class ExternalConnectionOrderDiagramGeneratorTest {

    @Test
    fun `trace all endpoints and generate external connection diagrams`() {
        val tracer = ExternalConnectionTracer()
        val harness = TracedControllerHarness(tracer)

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
        harness.traceDeleteTodo(todoId)
        harness.traceProjectLifecycle(collaboratorId)

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
                "externalConnectionDiagramOut",
                "build/reports/external-connection-order.md",
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
            "OpenAPI endpoints missing from ExternalConnectionOrderDiagramGeneratorTest: $missing",
        )
    }

    @Suppress("TooManyFunctions")
    private class TracedControllerHarness(private val tracer: ExternalConnectionTracer) {
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

        private val userContext = object : UserContext {
            override fun currentUserId(): UUID = activeUserId
        }

        private val passwordEncoder = object : PasswordEncoder {
            override fun encode(rawPassword: String): String = "hashed:$rawPassword"
            override fun matches(rawPassword: String, encodedPassword: String): Boolean =
                encodedPassword == "hashed:$rawPassword"
        }

        private val tokenIssuer = object : TokenIssuer {
            override fun issueToken(user: AuthenticatedUser): TokenResult =
                TokenResult("stub-jwt", "Bearer", EXPIRES_IN_SECONDS)
        }

        private val authController = AuthController(
            AuthService(userRepo, passwordEncoder, tokenIssuer, emailSender),
        )

        private val todoController = TodoController(
            TodoService(
                todoRepository = todoRepo,
                idempotencyRecordRepository = idempotencyRepo,
                todoEventPublisher = eventPublisher,
                userSettingsRepository = userSettingsRepo,
                cryptoHandler = TodoCryptoHandler(cryptoService),
                projectMemberRepository = projectMemberRepo,
            ),
            userContext,
        )

        private val projectController = ProjectController(
            ProjectService(
                projectRepository = projectRepo,
                projectMemberRepository = projectMemberRepo,
                userRepository = userRepo,
                todoRepository = todoRepo,
            ),
            userContext,
        )

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

        private val todoSubtaskController = TodoSubtaskController(
            TodoSubtaskService(
                todoRepository = todoRepo,
                todoSubtaskRepository = todoSubtaskRepo,
                projectMemberRepository = projectMemberRepo,
            ),
            userContext,
        )

        private val settingsController = UserSettingsController(
            UserSettingsService(userSettingsRepo),
            userContext,
        )

        private val adminController = AdminController(
            AdminStatsService(userRepo, todoRepo),
            AdminUserService(userRepo),
        )

        fun setCurrentUser(userId: UUID) {
            activeUserId = userId
        }

        fun registerCollaborator(username: String): UUID =
            requireNotNull(authController.registerUser(RegisterRequest(username, "secret123")).body?.id)

        fun traceRegister(): UUID = tracer.trace("POST /api/auth/register", transactionalReadOnly = false) {
            requireNotNull(authController.registerUser(RegisterRequest("alice", "secret123")).body?.id)
        }

        fun traceLogin() = tracer.trace("POST /api/auth/login", transactionalReadOnly = true) {
            authController.loginUser(LoginRequest("alice", "secret123"))
        }

        fun traceUpdateSettings() = tracer.trace("PUT /api/user/settings", transactionalReadOnly = false) {
            settingsController.updateUserSettings(
                UpdateUserSettingsRequest(
                    defaultPriority = ApiTodoPriority.HIGH,
                    defaultEstimatedMinutes = DEFAULT_ESTIMATED_MINUTES,
                    maxActiveTodos = MAX_ACTIVE_TODOS,
                ),
            )
        }

        fun traceGetSettings() = tracer.trace("GET /api/user/settings", transactionalReadOnly = true) {
            settingsController.getUserSettings()
        }

        fun traceCreateTodo(): UUID = tracer.trace("POST /api/todos", transactionalReadOnly = false) {
            val response = todoController.createTodo(
                "idem-key-1",
                CreateTodoRequest(
                    title = "Write diagram",
                    description = "Secret notes",
                    priority = ApiTodoPriority.HIGH,
                    tags = listOf("docs"),
                ),
            )
            requireNotNull(response.body?.id)
        }

        fun traceCreateTodoIdempotentReplay() = tracer.trace(
            "POST /api/todos (idempotent replay)",
            transactionalReadOnly = false,
        ) {
            todoController.createTodo(
                "idem-key-1",
                CreateTodoRequest(
                    title = "Write diagram",
                    description = "Secret notes",
                    priority = ApiTodoPriority.HIGH,
                    tags = listOf("docs"),
                ),
            )
        }

        fun traceListTodos() = tracer.trace("GET /api/todos", transactionalReadOnly = true) {
            todoController.listTodos(
                completed = null,
                priority = null,
                tag = null,
                q = null,
                dueBefore = null,
                dueAfter = null,
                sortBy = null,
                sortOrder = null,
                projectId = null,
            )
        }

        fun traceGetTodo(id: UUID) = tracer.trace("GET /api/todos/{id}", transactionalReadOnly = true) {
            todoController.getTodo(id)
        }

        fun traceUpdateTodo(id: UUID) = tracer.trace("PUT /api/todos/{id}", transactionalReadOnly = false) {
            todoController.updateTodo(
                id,
                UpdateTodoRequest(
                    title = "Write diagram v2",
                    completed = true,
                    description = "Updated secret notes",
                    priority = ApiTodoPriority.MEDIUM,
                    tags = listOf("docs"),
                ),
            )
        }

        fun tracePatchTodoReopen(id: UUID) = tracer.trace(
            "PATCH /api/todos/{id} (reopen)",
            transactionalReadOnly = false,
        ) {
            todoController.patchTodo(
                id,
                PatchTodoRequest(
                    completed = false,
                    description = "Reopened notes",
                ),
            )
        }

        fun traceAdminStats() = tracer.trace("GET /api/admin/stats", transactionalReadOnly = true) {
            adminController.getAdminStats()
        }

        fun traceAdminUsers() = tracer.trace("GET /api/admin/users", transactionalReadOnly = true) {
            adminController.getAdminUsers()
        }

        fun traceDeleteTodo(id: UUID) = tracer.trace("DELETE /api/todos/{id}", transactionalReadOnly = false) {
            todoController.deleteTodo(id)
        }

        fun traceSubtaskLifecycle(todoId: UUID) {
            val subtaskId = tracer.trace("POST /api/todos/{id}/subtasks", transactionalReadOnly = false) {
                requireNotNull(
                    todoSubtaskController.createTodoSubtask(
                        todoId,
                        CreateTodoSubtaskRequest("Step 1"),
                    ).body?.id,
                )
            }
            tracer.trace("GET /api/todos/{id}/subtasks", transactionalReadOnly = true) {
                todoSubtaskController.listTodoSubtasks(todoId)
            }
            tracer.trace("PATCH /api/todos/{id}/subtasks/{subtaskId}", transactionalReadOnly = false) {
                todoSubtaskController.patchTodoSubtask(
                    todoId,
                    subtaskId,
                    PatchTodoSubtaskRequest(completed = true),
                )
            }
            tracer.trace("DELETE /api/todos/{id}/subtasks/{subtaskId}", transactionalReadOnly = false) {
                todoSubtaskController.deleteTodoSubtask(todoId, subtaskId)
            }
        }

        fun traceProjectLifecycle(collaboratorId: UUID) {
            val projectId = tracer.trace("POST /api/projects", transactionalReadOnly = false) {
                requireNotNull(
                    projectController.createProject(CreateProjectRequest("Shared", "Team project")).body?.id,
                )
            }
            tracer.trace("GET /api/projects", transactionalReadOnly = true) {
                projectController.listProjects()
            }
            tracer.trace("GET /api/projects/{projectId}", transactionalReadOnly = true) {
                projectController.getProject(projectId)
            }
            tracer.trace("PUT /api/projects/{projectId}", transactionalReadOnly = false) {
                projectController.updateProject(projectId, UpdateProjectRequest("Shared v2", "Updated"))
            }
            tracer.trace("POST /api/projects/{projectId}/members", transactionalReadOnly = false) {
                projectController.addProjectMember(
                    projectId,
                    AddProjectMemberRequest(username = "bob", role = ApiProjectRole.MEMBER),
                )
            }
            tracer.trace("GET /api/projects/{projectId}/members", transactionalReadOnly = true) {
                projectController.listProjectMembers(projectId)
            }
            tracer.trace("DELETE /api/projects/{projectId}/members/{userId}", transactionalReadOnly = false) {
                projectController.removeProjectMember(projectId, collaboratorId)
            }
            tracer.trace("DELETE /api/projects/{projectId}", transactionalReadOnly = false) {
                projectController.deleteProject(projectId)
            }
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
