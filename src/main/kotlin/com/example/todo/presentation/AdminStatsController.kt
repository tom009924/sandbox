package com.example.todo.presentation

import com.example.todo.application.AdminStatsService
import com.example.todo.presentation.api.AdminApi
import com.example.todo.presentation.model.AdminStatsResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminStatsController(
    private val adminStatsService: AdminStatsService,
) : AdminApi {
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
}

