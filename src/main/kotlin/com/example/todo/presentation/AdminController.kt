package com.example.todo.presentation

import com.example.todo.application.AdminStatsService
import com.example.todo.application.AdminUserService
import com.example.todo.presentation.api.AdminApi
import com.example.todo.presentation.model.AdminStatsResponse
import com.example.todo.presentation.model.AdminUserResponse
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminController(
    private val adminStatsService: AdminStatsService,
    private val adminUserService: AdminUserService,
) : AdminApi {
    @Transactional(readOnly = true)
    override fun getAdminStats(): ResponseEntity<AdminStatsResponse> {
        val stats = adminStatsService.getStats()
        return ResponseEntity.ok(
            AdminStatsResponse(
                totalUsers = stats.totalUsers,
                totalTodos = stats.totalTodos,
                completedTodos = stats.completedTodos,
                activeTodos = stats.activeTodos,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun getAdminUsers(): ResponseEntity<List<AdminUserResponse>> {
        val users = adminUserService.getUsers().map { user ->
            AdminUserResponse(
                id = requireNotNull(user.id) { "User ID must not be null" },
                username = user.username,
            )
        }
        return ResponseEntity.ok(users)
    }
}

