package com.example.todo.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface SpringDataIdempotencyRecordRepository : JpaRepository<IdempotencyRecordJpaEntity, String> {
    fun deleteByExpiresAtBefore(instant: Instant)
}
