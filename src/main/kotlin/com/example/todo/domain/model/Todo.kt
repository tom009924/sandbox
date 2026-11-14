package com.example.todo.domain.model

import java.time.LocalDate
import java.util.UUID

data class Todo(
    val id: UUID? = null,
    val userId: UUID,
    val title: String,
    val completed: Boolean = false,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.MEDIUM,
    val estimatedMinutes: Int? = null,
    val tags: List<String> = emptyList(),
)
