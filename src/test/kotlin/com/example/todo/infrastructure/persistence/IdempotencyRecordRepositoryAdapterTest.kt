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
        val key = "key_${UUID.randomUUID()}"
        val record = IdempotencyRecord(
            key = key,
            requestFingerprint = "fp_${UUID.randomUUID()}",
            response = Todo(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                title = "Idempotent Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = Instant.now().plusSeconds(3600),
        )

        adapter.save(record)

        val found = adapter.findByKey(key)
        assertNotNull(found)
        assertEquals(key, found?.key)
        assertEquals("Idempotent Todo", found?.response?.title)
    }

    @Test
    fun `deleteExpiredBefore deletes only expired records via SpannerTemplate DML`() {
        val expiredKey = "expired_${UUID.randomUUID()}"
        val activeKey = "active_${UUID.randomUUID()}"
        val now = Instant.now()

        val expiredRecord = IdempotencyRecord(
            key = expiredKey,
            requestFingerprint = "fp_exp",
            response = Todo(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                title = "Expired Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = now.minusSeconds(60),
        )
        val activeRecord = IdempotencyRecord(
            key = activeKey,
            requestFingerprint = "fp_act",
            response = Todo(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                title = "Active Todo",
                priority = TodoPriority.LOW,
            ),
            expiresAt = now.plusSeconds(3600),
        )

        adapter.save(expiredRecord)
        adapter.save(activeRecord)

        adapter.deleteExpiredBefore(now)

        assertNull(adapter.findByKey(expiredKey))
        assertNotNull(adapter.findByKey(activeKey))
    }
}
