package com.example.todo.infrastructure.persistence

import com.example.todo.domain.repository.IdempotencyRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class IdempotencyCleanupScheduler(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
) {
    private val logger = LoggerFactory.getLogger(IdempotencyCleanupScheduler::class.java)
    private val clock: Clock = Clock.systemUTC()

    @Scheduled(fixedDelayString = "\${todo.idempotency.cleanup-interval-ms:$DEFAULT_CLEANUP_INTERVAL_MS}")
    fun cleanupExpiredRecords() {
        val now = clock.instant()
        idempotencyRecordRepository.deleteExpiredBefore(now)
        logger.debug("Cleaned up expired idempotency records before {}", now)
    }

    private companion object {
        const val DEFAULT_CLEANUP_INTERVAL_MS = 3_600_000L
    }
}
