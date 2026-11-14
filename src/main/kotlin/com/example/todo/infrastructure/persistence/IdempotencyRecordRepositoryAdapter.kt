package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.google.cloud.Timestamp
import com.google.cloud.spanner.Statement
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class IdempotencyRecordRepositoryAdapter(
    private val springDataRepository: SpringDataIdempotencyRecordRepository,
    private val spannerTemplate: SpannerTemplate,
    private val objectMapper: ObjectMapper,
) : IdempotencyRecordRepository {
    override fun findByUserIdAndKey(userId: UUID, key: String): IdempotencyRecord? =
        springDataRepository.findByUserIdAndKey(userId.toString(), key)?.toDomain()

    override fun save(record: IdempotencyRecord) {
        spannerTemplate.upsert(record.toEntity())
    }

    override fun deleteExpiredBefore(instant: Instant) {
        val statement = Statement.newBuilder("DELETE FROM idempotency_records WHERE expires_at < @expiresAt")
            .bind("expiresAt")
            .to(Timestamp.ofTimeSecondsAndNanos(instant.epochSecond, instant.nano))
            .build()
        spannerTemplate.executeDmlStatement(statement)
    }

    private fun IdempotencyRecordSpannerEntity.toDomain() = IdempotencyRecord(
        userId = UUID.fromString(userId),
        key = key,
        requestFingerprint = requestFingerprint,
        response = objectMapper.readValue(responseSnapshot, Todo::class.java),
        expiresAt = expiresAt,
    )

    private fun IdempotencyRecord.toEntity() = IdempotencyRecordSpannerEntity(
        userId = userId.toString(),
        key = key,
        requestFingerprint = requestFingerprint,
        responseSnapshot = objectMapper.writeValueAsString(response),
        expiresAt = expiresAt,
    )
}
