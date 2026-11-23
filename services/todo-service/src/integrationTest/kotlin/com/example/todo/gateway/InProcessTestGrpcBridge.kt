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
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.infrastructure.grpc.ProjectGrpcService
import com.example.todo.infrastructure.grpc.TodoGrpcService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.UUID
import com.example.identity.grpc.v1.AuthTokenResponse as ProtoAuthTokenResponse
import com.example.identity.grpc.v1.LoginRequest as ProtoLoginRequest
import com.example.identity.grpc.v1.RegisterRequest as ProtoRegisterRequest
import com.example.identity.grpc.v1.TodoPriority as ProtoIdentityPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest as ProtoUpdateUserSettingsRequest

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
