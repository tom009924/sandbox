package com.example.todo.domain.model

data class AdminStats(
    val totalUsers: Long,
    val totalTodos: Long,
    val completedTodos: Long,
    val activeTodos: Long,
)

