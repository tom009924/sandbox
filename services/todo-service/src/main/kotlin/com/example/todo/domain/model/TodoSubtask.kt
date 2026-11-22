package com.example.todo.domain.model

import java.util.UUID

data class TodoSubtask(
    val id: UUID? = null,
    val todoId: UUID,
    val title: String,
    val completed: Boolean = false,
    val position: Int,
)
