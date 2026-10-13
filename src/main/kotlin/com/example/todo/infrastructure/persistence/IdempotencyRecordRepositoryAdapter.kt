package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class IdempotencyRecordRepositoryAdapter(
    private val springDataRepository: SpringDataIdempotencyRecordRepository,
    private val objectMapper: ObjectMapper,
) : IdempotencyRecordRepository {
    override fun findByKey(key: String): IdempotencyRecord? =
        springDataRepository.findById(key).orElse(null)?.toDomain()

    override fun save(record: IdempotencyRecord) {
        springDataRepository.save(record.toEntity())
    }

    override fun deleteExpiredBefore(instant: Instant) {
        springDataRepository.deleteByExpiresAtBefore(instant)
    }

    private fun IdempotencyRecordJpaEntity.toDomain() = IdempotencyRecord(
        key = key,
        requestFingerprint = requestFingerprint,
        response = objectMapper.readValue(responseSnapshot, Todo::class.java),
        expiresAt = expiresAt,
    )

    private fun IdempotencyRecord.toEntity() = IdempotencyRecordJpaEntity(
        key = key,
        requestFingerprint = requestFingerprint,
        responseSnapshot = objectMapper.writeValueAsString(response),
        expiresAt = expiresAt,
    )
}
