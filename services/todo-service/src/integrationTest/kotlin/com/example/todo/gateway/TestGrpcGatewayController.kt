package com.example.todo.gateway

import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.ListUsersResponse
import com.example.identity.grpc.v1.UserMessage
import com.example.identity.grpc.v1.UserSettingsMessage
import com.example.todo.application.TokenIssuer
import com.example.todo.domain.exception.InvalidCredentialsException
import com.example.todo.domain.exception.UserAlreadyExistsException
import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.User
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.notification.EmailNotification
import com.example.todo.domain.notification.EmailNotificationException
import com.example.todo.domain.notification.EmailNotificationSender
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.domain.security.PasswordEncoder
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
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
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.infrastructure.grpc.ProjectGrpcService
import com.example.todo.infrastructure.grpc.TodoGrpcService
import com.example.todo.presentation.api.AdminApi
import com.example.todo.presentation.api.AuthApi
import com.example.todo.presentation.api.ProjectsApi
import com.example.todo.presentation.api.SettingsApi
import com.example.todo.presentation.api.TodoCommentsApi
import com.example.todo.presentation.api.TodoSubtasksApi
import com.example.todo.presentation.api.TodosApi
import com.example.todo.presentation.model.AddProjectMemberRequest
import com.example.todo.presentation.model.AdminStatsResponse
import com.example.todo.presentation.model.AdminUserResponse
import com.example.todo.presentation.model.AuthTokenResponse
import com.example.todo.presentation.model.CreateProjectRequest
import com.example.todo.presentation.model.CreateTodoCommentRequest
import com.example.todo.presentation.model.CreateTodoRequest
import com.example.todo.presentation.model.CreateTodoSubtaskRequest
import com.example.todo.presentation.model.LoginRequest
import com.example.todo.presentation.model.PatchTodoRequest
import com.example.todo.presentation.model.PatchTodoSubtaskRequest
import com.example.todo.presentation.model.ProjectMemberResponse
import com.example.todo.presentation.model.ProjectResponse
import com.example.todo.presentation.model.RegisterRequest
import com.example.todo.presentation.model.TodoCommentResponse
import com.example.todo.presentation.model.TodoSubtaskResponse
import com.example.todo.presentation.model.UpdateProjectRequest
import com.example.todo.presentation.model.UpdateTodoRequest
import com.example.todo.presentation.model.UpdateUserSettingsRequest
import com.example.todo.presentation.model.UserResponse
import com.example.todo.presentation.model.UserSettingsResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import jakarta.annotation.PreDestroy
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.example.identity.grpc.v1.AuthTokenResponse as ProtoAuthTokenResponse
import com.example.identity.grpc.v1.LoginRequest as ProtoLoginRequest
import com.example.identity.grpc.v1.RegisterRequest as ProtoRegisterRequest
import com.example.identity.grpc.v1.TodoPriority as ProtoIdentityPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest as ProtoUpdateUserSettingsRequest
import com.example.todo.grpc.v1.AddProjectMemberRequest as ProtoAddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest as ProtoCreateProjectRequest
import com.example.todo.grpc.v1.CreateTodoRequest as ProtoCreateTodoRequest
import com.example.todo.grpc.v1.PatchTodoRequest as ProtoPatchTodoRequest
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole
import com.example.todo.grpc.v1.SortOrder as ProtoSortOrder
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority
import com.example.todo.grpc.v1.TodoSortField as ProtoTodoSortField
import com.example.todo.grpc.v1.UpdateProjectRequest as ProtoUpdateProjectRequest
import com.example.todo.grpc.v1.UpdateTodoRequest as ProtoUpdateTodoRequest
import com.example.todo.presentation.model.ProjectRole as ApiProjectRole
import com.example.todo.presentation.model.SortOrder as ApiSortOrder
import com.example.todo.presentation.model.Todo as TodoResponse
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority
import com.example.todo.presentation.model.TodoSortField as ApiTodoSortField

/**
 * In-process gRPC bridge used by integration and property tests to verify that all incoming
 * HTTP REST traffic is translated to gRPC calls (`TodoService`, `ProjectService`, `IdentityService`)
 * before invoking `todo-service`.
 */
@Component
@Suppress("LongParameterList")
class InProcessTestGrpcBridge(
    todoGrpcService: TodoGrpcService,
    projectGrpcService: ProjectGrpcService,
    userRepository: UserRepository,
    userSettingsRepository: UserSettingsRepository,
    passwordEncoder: PasswordEncoder,
    tokenIssuer: TokenIssuer,
    emailNotificationSender: EmailNotificationSender,
) {
    private val serverName = InProcessServerBuilder.generateName()
    private val identityGrpcService = InProcessIdentityGrpcService(
        userRepository = userRepository,
        userSettingsRepository = userSettingsRepository,
        passwordEncoder = passwordEncoder,
        tokenIssuer = tokenIssuer,
        emailNotificationSender = emailNotificationSender,
    )
    private val server: Server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(todoGrpcService)
        .addService(projectGrpcService)
        .addService(identityGrpcService)
        .build()
        .start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName)
        .directExecutor()
        .build()

    val todoStub: TodoServiceGrpc.TodoServiceBlockingStub = TodoServiceGrpc.newBlockingStub(channel)
    val projectStub: ProjectServiceGrpc.ProjectServiceBlockingStub = ProjectServiceGrpc.newBlockingStub(channel)
    val identityStub: IdentityServiceGrpc.IdentityServiceBlockingStub = IdentityServiceGrpc.newBlockingStub(channel)

    @PreDestroy
    fun shutdown() {
        channel.shutdownNow()
        server.shutdownNow()
    }
}

private class InProcessIdentityGrpcService(
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenIssuer: TokenIssuer,
    private val emailNotificationSender: EmailNotificationSender,
) : IdentityServiceGrpc.IdentityServiceImplBase() {

    override fun register(
        request: ProtoRegisterRequest,
        responseObserver: StreamObserver<UserMessage>,
    ) {
        val trimmed = request.username.trim()
        if (userRepository.existsByUsername(trimmed)) {
            responseObserver.onError(
                Status.ALREADY_EXISTS
                    .withDescription(UserAlreadyExistsException(trimmed).message)
                    .asRuntimeException(),
            )
            return
        }
        val saved = userRepository.save(
            User(
                username = trimmed,
                passwordHash = passwordEncoder.encode(request.password),
            ),
        )
        try {
            emailNotificationSender.send(
                EmailNotification(
                    to = "$trimmed@example.com",
                    subject = "Welcome to Todo App",
                    content = "Welcome to Todo App, $trimmed!",
                ),
            )
        } catch (_: EmailNotificationException) {
            // Best-effort email notification
        }
        responseObserver.onNext(
            UserMessage.newBuilder()
                .setId(requireNotNull(saved.id).toString())
                .setUsername(saved.username)
                .build(),
        )
        responseObserver.onCompleted()
    }

    override fun login(
        request: ProtoLoginRequest,
        responseObserver: StreamObserver<ProtoAuthTokenResponse>,
    ) {
        val user = userRepository.findByUsername(request.username.trim())
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            responseObserver.onError(
                Status.UNAUTHENTICATED
                    .withDescription(InvalidCredentialsException().message)
                    .asRuntimeException(),
            )
            return
        }
        val token = tokenIssuer.issueToken(AuthenticatedUser(requireNotNull(user.id), user.username))
        responseObserver.onNext(
            ProtoAuthTokenResponse.newBuilder()
                .setToken(token.token)
                .setTokenType(token.tokenType)
                .setExpiresIn(token.expiresIn)
                .build(),
        )
        responseObserver.onCompleted()
    }

    override fun getUserSettings(
        request: GetUserSettingsRequest,
        responseObserver: StreamObserver<UserSettingsMessage>,
    ) {
        val userId = UUID.fromString(request.userId)
        val settings = userSettingsRepository.findByUserId(userId) ?: UserSettings.defaultFor(userId)
        responseObserver.onNext(settings.toProto())
        responseObserver.onCompleted()
    }

    override fun updateUserSettings(
        request: ProtoUpdateUserSettingsRequest,
        responseObserver: StreamObserver<UserSettingsMessage>,
    ) {
        val userId = UUID.fromString(request.userId)
        val saved = userSettingsRepository.save(
            UserSettings(
                userId = userId,
                defaultPriority = TodoPriority.valueOf(request.defaultPriority.name),
                defaultEstimatedMinutes = if (request.hasDefaultEstimatedMinutes()) {
                    request.defaultEstimatedMinutes
                } else {
                    null
                },
                maxActiveTodos = if (request.hasMaxActiveTodos()) request.maxActiveTodos else null,
            ),
        )
        responseObserver.onNext(saved.toProto())
        responseObserver.onCompleted()
    }

    override fun listUsers(
        request: ListUsersRequest,
        responseObserver: StreamObserver<ListUsersResponse>,
    ) {
        val users = userRepository.findAll().map {
            UserMessage.newBuilder()
                .setId(requireNotNull(it.id).toString())
                .setUsername(it.username)
                .build()
        }
        responseObserver.onNext(ListUsersResponse.newBuilder().addAllUsers(users).build())
        responseObserver.onCompleted()
    }

    private fun UserSettings.toProto(): UserSettingsMessage {
        val builder = UserSettingsMessage.newBuilder()
            .setUserId(userId.toString())
            .setDefaultPriority(ProtoIdentityPriority.valueOf(defaultPriority.name))
        defaultEstimatedMinutes?.let { builder.setDefaultEstimatedMinutes(it) }
        maxActiveTodos?.let { builder.setMaxActiveTodos(it) }
        return builder.build()
    }
}

@RestController
@Suppress("TooManyFunctions")
class TestGrpcGatewayController(
    private val bridge: InProcessTestGrpcBridge,
) : AuthApi, SettingsApi, TodosApi, TodoSubtasksApi, TodoCommentsApi, ProjectsApi, AdminApi {

    override fun registerUser(registerRequest: RegisterRequest): ResponseEntity<UserResponse> {
        val user = bridge.identityStub.register(
            ProtoRegisterRequest.newBuilder()
                .setUsername(registerRequest.username)
                .setPassword(registerRequest.password)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(
                id = UUID.fromString(user.id),
                username = user.username,
            ),
        )
    }

    override fun loginUser(loginRequest: LoginRequest): ResponseEntity<AuthTokenResponse> {
        val token = bridge.identityStub.login(
            ProtoLoginRequest.newBuilder()
                .setUsername(loginRequest.username)
                .setPassword(loginRequest.password)
                .build(),
        )
        return ResponseEntity.ok(
            AuthTokenResponse(
                token = token.token,
                tokenType = token.tokenType,
                expiresIn = token.expiresIn,
            ),
        )
    }

    override fun getUserSettings(): ResponseEntity<UserSettingsResponse> {
        val settings = bridge.identityStub.getUserSettings(
            GetUserSettingsRequest.newBuilder().setUserId(currentUserId().toString()).build(),
        )
        return ResponseEntity.ok(settings.toResponse())
    }

    override fun updateUserSettings(
        updateUserSettingsRequest: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val builder = ProtoUpdateUserSettingsRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setDefaultPriority(ProtoIdentityPriority.valueOf(updateUserSettingsRequest.defaultPriority.name))
        updateUserSettingsRequest.defaultEstimatedMinutes?.let { builder.setDefaultEstimatedMinutes(it) }
        updateUserSettingsRequest.maxActiveTodos?.let { builder.setMaxActiveTodos(it) }
        return ResponseEntity.ok(bridge.identityStub.updateUserSettings(builder.build()).toResponse())
    }

    @Suppress("LongParameterList")
    override fun listTodos(
        completed: Boolean?,
        priority: ApiTodoPriority?,
        tag: String?,
        q: String?,
        dueBefore: LocalDate?,
        dueAfter: LocalDate?,
        sortBy: ApiTodoSortField?,
        sortOrder: ApiSortOrder?,
        projectId: UUID?,
        assigneeId: UUID?,
    ): ResponseEntity<List<TodoResponse>> {
        val builder = ListTodosRequest.newBuilder().setUserId(currentUserId().toString())
        completed?.let { builder.setCompleted(it) }
        priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        tag?.let { builder.setTag(it) }
        q?.let { builder.setKeyword(it) }
        dueBefore?.let { builder.setDueBefore(it.toString()) }
        dueAfter?.let { builder.setDueAfter(it.toString()) }
        sortBy?.let { builder.setSortBy(ProtoTodoSortField.valueOf(it.name)) }
        sortOrder?.let { builder.setSortOrder(ProtoSortOrder.valueOf(it.name)) }
        projectId?.let { builder.setProjectId(it.toString()) }
        assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.listTodos(builder.build()).todosList.map { it.toResponse() })
    }

    override fun getTodo(id: UUID): ResponseEntity<TodoResponse> {
        val todo = bridge.todoStub.getTodo(
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
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoCreateTodoRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setIdempotencyKey(idempotencyKey)
            .setTitle(createTodoRequest.title)
            .addAllTags(createTodoRequest.tags.orEmpty())
        createTodoRequest.description?.let { builder.setDescription(it) }
        createTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        createTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        createTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        createTodoRequest.projectId?.let { builder.setProjectId(it.toString()) }
        createTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        val created = bridge.todoStub.createTodo(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun updateTodo(
        id: UUID,
        updateTodoRequest: UpdateTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoUpdateTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
            .setTitle(updateTodoRequest.title)
            .setCompleted(updateTodoRequest.completed)
            .addAllTags(updateTodoRequest.tags.orEmpty())
        updateTodoRequest.description?.let { builder.setDescription(it) }
        updateTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        updateTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        updateTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        updateTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.updateTodo(builder.build()).toResponse())
    }

    override fun patchTodo(
        id: UUID,
        patchTodoRequest: PatchTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoPatchTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
        patchTodoRequest.title?.let { builder.setTitle(it) }
        patchTodoRequest.completed?.let { builder.setCompleted(it) }
        patchTodoRequest.description?.let { builder.setDescription(it) }
        patchTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        patchTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        patchTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        if (patchTodoRequest.tags != null) {
            builder.setHasTags(true).addAllTags(patchTodoRequest.tags)
        }
        patchTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.patchTodo(builder.build()).toResponse())
    }

    override fun deleteTodo(id: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteTodo(
            DeleteTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listTodoSubtasks(id: UUID): ResponseEntity<List<TodoSubtaskResponse>> {
        val subtasks = bridge.todoStub.listSubtasks(
            ListSubtasksRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        ).subtasksList
        return ResponseEntity.ok(subtasks.map { it.toResponse() })
    }

    override fun createTodoSubtask(
        id: UUID,
        createTodoSubtaskRequest: CreateTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val created = bridge.todoStub.createSubtask(
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
        return ResponseEntity.ok(bridge.todoStub.patchSubtask(builder.build()).toResponse())
    }

    override fun deleteTodoSubtask(id: UUID, subtaskId: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteSubtask(
            DeleteSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setSubtaskId(subtaskId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listTodoComments(id: UUID): ResponseEntity<List<TodoCommentResponse>> {
        val comments = bridge.todoStub.listComments(
            ListCommentsRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        ).commentsList
        return ResponseEntity.ok(comments.map { it.toResponse() })
    }

    override fun createTodoComment(
        id: UUID,
        createTodoCommentRequest: CreateTodoCommentRequest,
    ): ResponseEntity<TodoCommentResponse> {
        val created = bridge.todoStub.createComment(
            CreateCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .setContent(createTodoCommentRequest.content)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun deleteTodoComment(id: UUID, commentId: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteComment(
            DeleteCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setCommentId(commentId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listProjects(): ResponseEntity<List<ProjectResponse>> {
        val projects = bridge.projectStub.listProjects(
            ListProjectsRequest.newBuilder().setUserId(currentUserId().toString()).build(),
        ).projectsList
        return ResponseEntity.ok(projects.map { it.toResponse() })
    }

    override fun createProject(createProjectRequest: CreateProjectRequest): ResponseEntity<ProjectResponse> {
        val builder = ProtoCreateProjectRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setName(createProjectRequest.name)
        createProjectRequest.description?.let { builder.setDescription(it) }
        val created = bridge.projectStub.createProject(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun getProject(projectId: UUID): ResponseEntity<ProjectResponse> {
        val project = bridge.projectStub.getProject(
            GetProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(project.toResponse())
    }

    override fun updateProject(
        projectId: UUID,
        updateProjectRequest: UpdateProjectRequest,
    ): ResponseEntity<ProjectResponse> {
        val builder = ProtoUpdateProjectRequest.newBuilder()
            .setProjectId(projectId.toString())
            .setUserId(currentUserId().toString())
            .setName(updateProjectRequest.name)
        updateProjectRequest.description?.let { builder.setDescription(it) }
        return ResponseEntity.ok(bridge.projectStub.updateProject(builder.build()).toResponse())
    }

    override fun deleteProject(projectId: UUID): ResponseEntity<Unit> {
        bridge.projectStub.deleteProject(
            DeleteProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listProjectMembers(projectId: UUID): ResponseEntity<List<ProjectMemberResponse>> {
        val members = bridge.projectStub.listProjectMembers(
            ListProjectMembersRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        ).membersList
        return ResponseEntity.ok(members.map { it.toResponse() })
    }

    override fun addProjectMember(
        projectId: UUID,
        addProjectMemberRequest: AddProjectMemberRequest,
    ): ResponseEntity<ProjectMemberResponse> {
        val role = ProtoProjectRole.valueOf((addProjectMemberRequest.role ?: ApiProjectRole.MEMBER).name)
        val added = bridge.projectStub.addProjectMember(
            ProtoAddProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentUserId().toString())
                .setUsername(addProjectMemberRequest.username)
                .setRole(role)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(added.toResponse())
    }

    override fun removeProjectMember(projectId: UUID, userId: UUID): ResponseEntity<Unit> {
        bridge.projectStub.removeProjectMember(
            RemoveProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentUserId().toString())
                .setTargetUserId(userId.toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun getAdminStats(): ResponseEntity<AdminStatsResponse> {
        val stats = bridge.todoStub.getAdminStats(GetAdminStatsRequest.getDefaultInstance())
        return ResponseEntity.ok(
            AdminStatsResponse(
                totalUsers = stats.totalUsers,
                totalTodos = stats.totalTodos,
                completedTodos = stats.completedTodos,
                activeTodos = stats.activeTodos,
            ),
        )
    }

    override fun getAdminUsers(): ResponseEntity<List<AdminUserResponse>> {
        val users = bridge.identityStub.listUsers(ListUsersRequest.getDefaultInstance()).usersList.map {
            AdminUserResponse(
                id = UUID.fromString(it.id),
                username = it.username,
            )
        }
        return ResponseEntity.ok(users)
    }

    private fun currentUserId(): UUID {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
        return principal.userId
    }
}

@RestControllerAdvice
class TestGrpcGatewayExceptionHandler {
    private val logger = LoggerFactory.getLogger(TestGrpcGatewayExceptionHandler::class.java)

    @ExceptionHandler(StatusRuntimeException::class)
    fun handleGrpcException(ex: StatusRuntimeException): ResponseEntity<Map<String, String>> {
        val httpStatus = when (ex.status.code) {
            Status.Code.INVALID_ARGUMENT,
            Status.Code.FAILED_PRECONDITION,
            -> HttpStatus.BAD_REQUEST
            Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.ALREADY_EXISTS -> HttpStatus.CONFLICT
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val message = ex.status.description ?: httpStatus.reasonPhrase
        return ResponseEntity.status(httpStatus).body(mapOf("message" to message))
    }

    @ExceptionHandler(
        ConstraintViolationException::class,
        HandlerMethodValidationException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        MissingRequestHeaderException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleBadRequest(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to "Invalid request parameters"))

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode)
            .body(mapOf("message" to (ex.reason ?: "Request failed")))

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Unhandled test gateway exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("message" to "Internal server error"))
    }
}

private fun UserSettingsMessage.toResponse() = UserSettingsResponse(
    userId = UUID.fromString(userId),
    defaultPriority = ApiTodoPriority.valueOf(defaultPriority.name),
    defaultEstimatedMinutes = if (hasDefaultEstimatedMinutes()) defaultEstimatedMinutes else null,
    maxActiveTodos = if (hasMaxActiveTodos()) maxActiveTodos else null,
)

private fun TodoMessage.toResponse() = TodoResponse(
    id = UUID.fromString(id),
    userId = UUID.fromString(userId),
    title = title,
    completed = completed,
    description = if (hasDescription()) description else null,
    dueDate = if (hasDueDate()) LocalDate.parse(dueDate) else null,
    priority = ApiTodoPriority.valueOf(priority.name),
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

private fun ProjectMessage.toResponse() = ProjectResponse(
    id = UUID.fromString(id),
    name = name,
    description = if (hasDescription()) description else null,
    createdBy = UUID.fromString(createdBy),
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

private fun ProjectMemberMessage.toResponse() = ProjectMemberResponse(
    projectId = UUID.fromString(projectId),
    userId = UUID.fromString(userId),
    username = username,
    role = ApiProjectRole.valueOf(role.name),
    joinedAt = Instant.parse(joinedAt).atOffset(ZoneOffset.UTC),
)
