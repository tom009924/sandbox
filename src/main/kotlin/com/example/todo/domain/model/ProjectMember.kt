package com.example.todo.domain.model

import java.time.Instant
import java.util.UUID

data class ProjectMember(
    val projectId: UUID,
    val userId: UUID,
    val role: ProjectRole,
    val joinedAt: Instant,
)
