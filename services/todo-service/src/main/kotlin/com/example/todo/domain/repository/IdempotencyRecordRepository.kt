package com.example.todo.domain.repository

import com.example.todo.domain.model.IdempotencyRecord
import java.time.Instant
import java.util.UUID

interface IdempotencyRecordRepository {
    fun findByUserIdAndKey(userId: UUID, key: String): IdempotencyRecord?

    fun save(record: IdempotencyRecord)

    fun deleteExpiredBefore(instant: Instant)
}
