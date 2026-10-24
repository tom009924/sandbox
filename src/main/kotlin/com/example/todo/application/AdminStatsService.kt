package com.example.todo.application

import com.example.todo.domain.model.AdminStats
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository

class AdminStatsService(
    private val userRepository: UserRepository,
    private val todoRepository: TodoRepository,
) {
    fun getStats(): AdminStats {
        val totalUsers = userRepository.count()
        val totalTodos = todoRepository.count()
        val completedTodos = todoRepository.countByCompleted(true)
        val activeTodos = totalTodos - completedTodos
        return AdminStats(
            totalUsers = totalUsers,
            totalTodos = totalTodos,
            completedTodos = completedTodos,
            activeTodos = activeTodos,
        )
    }
}

