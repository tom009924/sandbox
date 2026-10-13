package com.example.todo.domain.repository

import com.example.todo.domain.model.IdempotencyRecord
import java.time.Instant

interface IdempotencyRecordRepository {
    fun findByKey(key: String): IdempotencyRecord?

    fun save(record: IdempotencyRecord)

    fun deleteExpiredBefore(instant: Instant)
}
