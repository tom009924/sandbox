package com.example.gateway.presentation

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.grpc.TodoGrpcClient
import com.example.gateway.presentation.api.AdminApi
import com.example.gateway.presentation.model.AdminStatsResponse
import com.example.gateway.presentation.model.AdminUserResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AdminGatewayController(
    private val identityGrpcClient: IdentityGrpcClient,
    private val todoGrpcClient: TodoGrpcClient,
) : AdminApi {

    override fun getAdminUsers(): ResponseEntity<List<AdminUserResponse>> {
        val users = identityGrpcClient.listUsers().map {
            AdminUserResponse(
                id = UUID.fromString(it.id),
                username = it.username,
            )
        }
        return ResponseEntity.ok(users)
    }

    override fun getAdminStats(): ResponseEntity<AdminStatsResponse> {
        val stats = todoGrpcClient.getAdminStats()
        return ResponseEntity.ok(
            AdminStatsResponse(
                totalUsers = stats.totalUsers,
                totalTodos = stats.totalTodos,
                completedTodos = stats.completedTodos,
                activeTodos = stats.activeTodos,
            ),
        )
    }
}
