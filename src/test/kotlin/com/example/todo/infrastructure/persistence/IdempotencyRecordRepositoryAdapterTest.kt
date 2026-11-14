package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest
class IdempotencyRecordRepositoryAdapterTest @Autowired constructor(
    private val adapter: IdempotencyRecordRepositoryAdapter,
) {
    @Test
    fun `save and find idempotency record`() {
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
            expiresAt = Instant.now().plusSeconds(3600),
        )

        adapter.save(record)

        val found = adapter.findByUserIdAndKey(userId, key)
        assertNotNull(found)
        assertEquals(userId, found?.userId)
        assertEquals(key, found?.key)
        assertEquals("Idempotent Todo", found?.response?.title)
    }

    @Test
    fun `deleteExpiredBefore deletes only expired records via SpannerTemplate DML`() {
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
            expiresAt = now.minusSeconds(60),
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
            expiresAt = now.plusSeconds(3600),
        )

        adapter.save(expiredRecord)
        adapter.save(activeRecord)

        adapter.deleteExpiredBefore(now)

        assertNull(adapter.findByUserIdAndKey(expiredUserId, expiredKey))
        assertNotNull(adapter.findByUserIdAndKey(activeUserId, activeKey))
    }
}
