package com.example.todo.domain.model

import java.time.Instant
import java.util.UUID

data class TodoComment(
    val id: UUID? = null,
    val todoId: UUID,
    val userId: UUID,
    val content: String,
    val createdAt: Instant,
)
