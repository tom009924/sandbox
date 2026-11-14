package com.example.todo.domain.model

import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val userId: UUID,
    val key: String,
    val requestFingerprint: String,
    val response: Todo,
    val expiresAt: Instant,
)
