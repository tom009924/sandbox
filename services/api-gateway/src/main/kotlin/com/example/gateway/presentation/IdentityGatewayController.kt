package com.example.gateway.presentation

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.presentation.api.AuthApi
import com.example.gateway.presentation.api.SettingsApi
import com.example.gateway.presentation.model.AuthTokenResponse
import com.example.gateway.presentation.model.LoginRequest
import com.example.gateway.presentation.model.RegisterRequest
import com.example.gateway.presentation.model.TodoPriority
import com.example.gateway.presentation.model.UpdateUserSettingsRequest
import com.example.gateway.presentation.model.UserResponse
import com.example.gateway.presentation.model.UserSettingsResponse
import com.example.identity.grpc.v1.UserSettingsMessage
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.identity.grpc.v1.TodoPriority as ProtoTodoPriority

@RestController
class IdentityGatewayController(
    private val identityGrpcClient: IdentityGrpcClient,
) : AuthApi, SettingsApi {

    override fun registerUser(registerRequest: RegisterRequest): ResponseEntity<UserResponse> {
        val user = identityGrpcClient.register(registerRequest.username, registerRequest.password)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(
                id = UUID.fromString(user.id),
                username = user.username,
            ),
        )
    }

    override fun loginUser(loginRequest: LoginRequest): ResponseEntity<AuthTokenResponse> {
        val token = identityGrpcClient.login(loginRequest.username, loginRequest.password)
        return ResponseEntity.ok(
            AuthTokenResponse(
                token = token.token,
                tokenType = token.tokenType,
                expiresIn = token.expiresIn,
            ),
        )
    }

    override fun getUserSettings(): ResponseEntity<UserSettingsResponse> {
        val userId = currentUserId()
        return ResponseEntity.ok(identityGrpcClient.getUserSettings(userId).toResponse())
    }

    override fun updateUserSettings(
        updateUserSettingsRequest: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val userId = currentUserId()
        val updated = identityGrpcClient.updateUserSettings(
            userId = userId,
            defaultPriority = updateUserSettingsRequest.defaultPriority.toProto(),
            defaultEstimatedMinutes = updateUserSettingsRequest.defaultEstimatedMinutes,
            maxActiveTodos = updateUserSettingsRequest.maxActiveTodos,
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun UserSettingsMessage.toResponse() = UserSettingsResponse(
        userId = UUID.fromString(userId),
        defaultPriority = defaultPriority.toApi(),
        defaultEstimatedMinutes = if (hasDefaultEstimatedMinutes()) defaultEstimatedMinutes else null,
        maxActiveTodos = if (hasMaxActiveTodos()) maxActiveTodos else null,
    )

    private fun TodoPriority.toProto(): ProtoTodoPriority = when (this) {
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
}
