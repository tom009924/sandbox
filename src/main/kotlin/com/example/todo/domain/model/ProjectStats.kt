package com.example.todo.domain.model

import java.util.UUID

data class ProjectStats(
    val projectId: UUID,
    val totalTodos: Long,
    val completedTodos: Long,
    val activeTodos: Long,
    val overdueTodos: Long,
    val highPriorityTodos: Long,
    val mediumPriorityTodos: Long,
    val lowPriorityTodos: Long,
)
