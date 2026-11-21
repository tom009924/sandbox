package com.example.gateway.presentation

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.security.AuthenticatedUser
import com.example.identity.grpc.v1.UserSettingsMessage
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import com.example.identity.grpc.v1.TodoPriority as ProtoTodoPriority

enum class GatewayTodoPriority {
    LOW,
    MEDIUM,
    HIGH,
}

data class RegisterRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    val username: String,
    @field:NotBlank
    @field:Size(min = 8, max = 100)
    val password: String,
)

data class LoginRequest(
    @field:NotBlank
    val username: String,
    @field:NotBlank
    val password: String,
)

data class UserResponse(
    val id: UUID,
    val username: String,
)

data class AuthTokenResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
)

data class UpdateUserSettingsRequest(
    val defaultPriority: GatewayTodoPriority = GatewayTodoPriority.MEDIUM,
    @field:Min(1)
    val defaultEstimatedMinutes: Int? = null,
    @field:Min(1)
    val maxActiveTodos: Int? = null,
)

data class UserSettingsResponse(
    val userId: UUID,
    val defaultPriority: GatewayTodoPriority,
    val defaultEstimatedMinutes: Int?,
    val maxActiveTodos: Int?,
)

@RestController
class IdentityGatewayController(
    private val identityGrpcClient: IdentityGrpcClient,
) {
    @PostMapping("/api/auth/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<UserResponse> {
        val user = identityGrpcClient.register(request.username, request.password)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(
                id = UUID.fromString(user.id),
                username = user.username,
            ),
        )
    }

    @PostMapping("/api/auth/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<AuthTokenResponse> {
        val token = identityGrpcClient.login(request.username, request.password)
        return ResponseEntity.ok(
            AuthTokenResponse(
                token = token.token,
                tokenType = token.tokenType,
                expiresIn = token.expiresIn,
            ),
        )
    }

    @GetMapping("/api/user/settings")
    fun getUserSettings(): ResponseEntity<UserSettingsResponse> {
        val userId = currentUserId()
        return ResponseEntity.ok(identityGrpcClient.getUserSettings(userId).toResponse())
    }

    @PutMapping("/api/user/settings")
    fun updateUserSettings(
        @Valid @RequestBody request: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val userId = currentUserId()
        val updated = identityGrpcClient.updateUserSettings(
            userId = userId,
            defaultPriority = request.defaultPriority.toProto(),
            defaultEstimatedMinutes = request.defaultEstimatedMinutes,
            maxActiveTodos = request.maxActiveTodos,
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    @GetMapping("/api/admin/users")
    fun listAdminUsers(): ResponseEntity<List<UserResponse>> {
        val users = identityGrpcClient.listUsers().map {
            UserResponse(
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

    private fun UserSettingsMessage.toResponse() = UserSettingsResponse(
        userId = UUID.fromString(userId),
        defaultPriority = defaultPriority.toApi(),
        defaultEstimatedMinutes = if (hasDefaultEstimatedMinutes()) defaultEstimatedMinutes else null,
        maxActiveTodos = if (hasMaxActiveTodos()) maxActiveTodos else null,
    )

    private fun GatewayTodoPriority.toProto(): ProtoTodoPriority = when (this) {
        GatewayTodoPriority.LOW -> ProtoTodoPriority.LOW
        GatewayTodoPriority.MEDIUM -> ProtoTodoPriority.MEDIUM
        GatewayTodoPriority.HIGH -> ProtoTodoPriority.HIGH
    }

    private fun ProtoTodoPriority.toApi(): GatewayTodoPriority = when (this) {
        ProtoTodoPriority.LOW -> GatewayTodoPriority.LOW
        ProtoTodoPriority.MEDIUM,
        ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
        ProtoTodoPriority.UNRECOGNIZED,
        -> GatewayTodoPriority.MEDIUM
        ProtoTodoPriority.HIGH -> GatewayTodoPriority.HIGH
    }
}
