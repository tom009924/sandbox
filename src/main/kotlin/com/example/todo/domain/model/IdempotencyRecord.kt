package com.example.todo.domain.model

import java.time.Instant

data class IdempotencyRecord(
    val key: String,
    val requestFingerprint: String,
    val response: Todo,
    val expiresAt: Instant,
)
