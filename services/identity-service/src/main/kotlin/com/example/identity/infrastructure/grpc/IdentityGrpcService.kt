package com.example.identity.infrastructure.grpc

import com.example.identity.application.AdminUserService
import com.example.identity.application.AuthService
import com.example.identity.application.UserSettingsCommand
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.exception.InvalidCredentialsException
import com.example.identity.domain.exception.UserAlreadyExistsException
import com.example.identity.domain.exception.UserNotFoundException
import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.User
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserRepository
import com.example.identity.grpc.v1.AuthTokenResponse
import com.example.identity.grpc.v1.BatchGetUsersRequest
import com.example.identity.grpc.v1.CountUsersRequest
import com.example.identity.grpc.v1.CountUsersResponse
import com.example.identity.grpc.v1.GetUserByUsernameRequest
import com.example.identity.grpc.v1.GetUserRequest
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.ListUsersResponse
import com.example.identity.grpc.v1.LoginRequest
import com.example.identity.grpc.v1.RegisterRequest
import com.example.identity.grpc.v1.UpdateUserSettingsRequest
import com.example.identity.grpc.v1.UserMessage
import com.example.identity.grpc.v1.UserSettingsMessage
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import com.example.identity.grpc.v1.TodoPriority as ProtoTodoPriority

@Component
class IdentityGrpcService(
    private val authService: AuthService,
    private val userSettingsService: UserSettingsService,
    private val adminUserService: AdminUserService,
    private val userRepository: UserRepository,
) : IdentityServiceGrpc.IdentityServiceImplBase() {

    override fun register(
        request: RegisterRequest,
        responseObserver: StreamObserver<UserMessage>,
    ) {
        executeRpc(responseObserver) {
            val user = authService.register(request.username, request.password)
            user.toProto()
        }
    }

    override fun login(
        request: LoginRequest,
        responseObserver: StreamObserver<AuthTokenResponse>,
    ) {
        executeRpc(responseObserver) {
            val result = authService.login(request.username, request.password)
            AuthTokenResponse.newBuilder()
                .setToken(result.token)
                .setTokenType(result.tokenType)
                .setExpiresIn(result.expiresIn)
                .build()
        }
    }

    override fun getUserSettings(
        request: GetUserSettingsRequest,
        responseObserver: StreamObserver<UserSettingsMessage>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            userSettingsService.getSettings(userId).toProto()
        }
    }

    override fun updateUserSettings(
        request: UpdateUserSettingsRequest,
        responseObserver: StreamObserver<UserSettingsMessage>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            val command = UserSettingsCommand(
                defaultPriority = request.defaultPriority.toDomain(),
                defaultEstimatedMinutes = if (request.hasDefaultEstimatedMinutes()) {
                    request.defaultEstimatedMinutes
                } else {
                    null
                },
                maxActiveTodos = if (request.hasMaxActiveTodos()) {
                    request.maxActiveTodos
                } else {
                    null
                },
            )
            userSettingsService.updateSettings(userId, command).toProto()
        }
    }

    override fun listUsers(
        request: ListUsersRequest,
        responseObserver: StreamObserver<ListUsersResponse>,
    ) {
        executeRpc(responseObserver) {
            val users = adminUserService.getUsers().map { it.toProto() }
            ListUsersResponse.newBuilder().addAllUsers(users).build()
        }
    }

    override fun getUser(
        request: GetUserRequest,
        responseObserver: StreamObserver<UserMessage>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.id, "id")
            val user = userRepository.findById(userId) ?: throw UserNotFoundException(userId)
            user.toProto()
        }
    }

    override fun batchGetUsers(
        request: BatchGetUsersRequest,
        responseObserver: StreamObserver<ListUsersResponse>,
    ) {
        executeRpc(responseObserver) {
            val ids = request.idsList.distinct().map { parseUuid(it, "ids") }
            val users = userRepository.findAllByIdIn(ids).map { it.toProto() }
            ListUsersResponse.newBuilder().addAllUsers(users).build()
        }
    }

    override fun getUserByUsername(
        request: GetUserByUsernameRequest,
        responseObserver: StreamObserver<UserMessage>,
    ) {
        executeRpc(responseObserver) {
            val user = userRepository.findByUsername(request.username.trim())
                ?: throw NoSuchElementException("User not found: ${request.username}")
            user.toProto()
        }
    }

    override fun countUsers(
        request: CountUsersRequest,
        responseObserver: StreamObserver<CountUsersResponse>,
    ) {
        executeRpc(responseObserver) {
            CountUsersResponse.newBuilder().setCount(userRepository.count()).build()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> executeRpc(
        responseObserver: StreamObserver<T>,
        block: () -> T,
    ) {
        try {
            responseObserver.onNext(block())
            responseObserver.onCompleted()
        } catch (ex: UserAlreadyExistsException) {
            responseObserver.onError(Status.ALREADY_EXISTS.withDescription(ex.message).asRuntimeException())
        } catch (ex: InvalidCredentialsException) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription(ex.message).asRuntimeException())
        } catch (ex: UserNotFoundException) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(ex.message).asRuntimeException())
        } catch (ex: NoSuchElementException) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(ex.message).asRuntimeException())
        } catch (ex: IllegalArgumentException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.message).asRuntimeException())
        } catch (ex: Exception) {
            logger.error("Unexpected error while executing identity gRPC call", ex)
            responseObserver.onError(Status.INTERNAL.withDescription("Internal server error").asRuntimeException())
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(IdentityGrpcService::class.java)
    }
}

private fun parseUuid(raw: String, fieldName: String): UUID = try {
    UUID.fromString(raw)
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid UUID for '$fieldName': $raw", ex)
}

private fun User.toProto(): UserMessage = UserMessage.newBuilder()
    .setId(requireNotNull(id) { "User id must not be null" }.toString())
    .setUsername(username)
    .build()

private fun UserSettings.toProto(): UserSettingsMessage {
    val builder = UserSettingsMessage.newBuilder()
        .setUserId(userId.toString())
        .setDefaultPriority(defaultPriority.toProto())
    defaultEstimatedMinutes?.let { builder.setDefaultEstimatedMinutes(it) }
    maxActiveTodos?.let { builder.setMaxActiveTodos(it) }
    return builder.build()
}

private fun TodoPriority.toProto(): ProtoTodoPriority = when (this) {
    TodoPriority.LOW -> ProtoTodoPriority.LOW
    TodoPriority.MEDIUM -> ProtoTodoPriority.MEDIUM
    TodoPriority.HIGH -> ProtoTodoPriority.HIGH
}

private fun ProtoTodoPriority.toDomain(): TodoPriority = when (this) {
    ProtoTodoPriority.LOW -> TodoPriority.LOW
    ProtoTodoPriority.MEDIUM,
    ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
    ProtoTodoPriority.UNRECOGNIZED,
    -> TodoPriority.MEDIUM
    ProtoTodoPriority.HIGH -> TodoPriority.HIGH
}
