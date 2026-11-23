package com.example.todo.gateway

import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.presentation.api.AdminApi
import com.example.todo.presentation.api.AuthApi
import com.example.todo.presentation.api.SettingsApi
import com.example.todo.presentation.model.AdminStatsResponse
import com.example.todo.presentation.model.AdminUserResponse
import com.example.todo.presentation.model.AuthTokenResponse
import com.example.todo.presentation.model.LoginRequest
import com.example.todo.presentation.model.RegisterRequest
import com.example.todo.presentation.model.UpdateUserSettingsRequest
import com.example.todo.presentation.model.UserResponse
import com.example.todo.presentation.model.UserSettingsResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.identity.grpc.v1.LoginRequest as ProtoLoginRequest
import com.example.identity.grpc.v1.RegisterRequest as ProtoRegisterRequest
import com.example.identity.grpc.v1.TodoPriority as ProtoIdentityPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest as ProtoUpdateUserSettingsRequest

@RestController
class TestIdentityGatewayController(
    private val bridge: InProcessTestGrpcBridge,
) : AuthApi, SettingsApi, AdminApi {

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
            GetUserSettingsRequest.newBuilder().setUserId(currentAuthenticatedUserId().toString()).build(),
        )
        return ResponseEntity.ok(settings.toResponse())
    }

    override fun updateUserSettings(
        updateUserSettingsRequest: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val builder = ProtoUpdateUserSettingsRequest.newBuilder()
            .setUserId(currentAuthenticatedUserId().toString())
            .setDefaultPriority(ProtoIdentityPriority.valueOf(updateUserSettingsRequest.defaultPriority.name))
        updateUserSettingsRequest.defaultEstimatedMinutes?.let { builder.setDefaultEstimatedMinutes(it) }
        updateUserSettingsRequest.maxActiveTodos?.let { builder.setMaxActiveTodos(it) }
        return ResponseEntity.ok(bridge.identityStub.updateUserSettings(builder.build()).toResponse())
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
}
