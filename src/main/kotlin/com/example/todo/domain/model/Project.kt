package com.example.todo.domain.model

import java.time.Instant
import java.util.UUID

data class Project(
    val id: UUID? = null,
    val name: String,
    val description: String? = null,
    val createdBy: UUID,
    val createdAt: Instant,
)
