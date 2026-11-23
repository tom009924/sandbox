package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.IdempotencyRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["identity.service.enabled=false"])
class IdempotencyRecordRepositoryAdapterTest @Autowired constructor(
    private val adapter: IdempotencyRecordRepositoryAdapter,
    private val scheduler: IdempotencyCleanupScheduler,
) {
    @Test
    fun saveAndFindIdempotencyRecord() {
        val userId = UUID.randomUUID()
        val key = "key_${UUID.randomUUID()}"
        val record = IdempotencyRecord(
            userId = userId,
            key = key,
            requestFingerprint = "fp_${UUID.randomUUID()}",
            response = Todo(
                id = UUID.randomUUID(),
                userId = userId,
                title = "Idempotent Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = Instant.now().plusSeconds(ONE_HOUR_SECONDS),
        )

        adapter.save(record)

        val found = adapter.findByUserIdAndKey(userId, key)
        assertNotNull(found)
        assertEquals(userId, found?.userId)
        assertEquals(key, found?.key)
        assertEquals("Idempotent Todo", found?.response?.title)
    }

    @Test
    fun deleteExpiredBeforeDeletesOnlyExpiredRecordsViaSpannerTemplateDml() {
        val expiredUserId = UUID.randomUUID()
        val activeUserId = UUID.randomUUID()
        val expiredKey = "expired_${UUID.randomUUID()}"
        val activeKey = "active_${UUID.randomUUID()}"
        val now = Instant.now()

        val expiredRecord = IdempotencyRecord(
            userId = expiredUserId,
            key = expiredKey,
            requestFingerprint = "fp_exp",
            response = Todo(
                id = UUID.randomUUID(),
                userId = expiredUserId,
                title = "Expired Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = now.minusSeconds(ONE_MINUTE_SECONDS),
        )
        val activeRecord = IdempotencyRecord(
            userId = activeUserId,
            key = activeKey,
            requestFingerprint = "fp_act",
            response = Todo(
                id = UUID.randomUUID(),
                userId = activeUserId,
                title = "Active Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = now.plusSeconds(ONE_HOUR_SECONDS),
        )

        adapter.save(expiredRecord)
        adapter.save(activeRecord)

        adapter.deleteExpiredBefore(now)
        scheduler.cleanupExpiredRecords()

        assertNull(adapter.findByUserIdAndKey(expiredUserId, expiredKey))
        assertNotNull(adapter.findByUserIdAndKey(activeUserId, activeKey))
    }

    @Test
    fun cleanupExpiredRecordsDelegatesToRepository() {
        val mockRepo = mock(IdempotencyRecordRepository::class.java)
        val unitScheduler = IdempotencyCleanupScheduler(mockRepo)
        unitScheduler.cleanupExpiredRecords()
        verify(mockRepo).deleteExpiredBefore(any() ?: Instant.EPOCH)
    }

    private companion object {
        const val ONE_HOUR_SECONDS = 3600L
        const val ONE_MINUTE_SECONDS = 60L
    }
}
