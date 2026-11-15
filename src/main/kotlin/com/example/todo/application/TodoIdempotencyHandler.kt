package com.example.todo.application

import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.IdempotencyRecordRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/**
 * Handles idempotent Todo creation with striped per-(userId, key) locking,
 * request fingerprinting, and 24-hour record retention.
 */
class TodoIdempotencyHandler(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    fun execute(
        userId: UUID,
        key: String,
        command: TodoCommand,
        onCreate: () -> Todo,
    ): Todo {
        val lockIndex = (((userId.hashCode() * STRIPE_HASH_PRIME) + key.hashCode()) and Int.MAX_VALUE) % LOCK_STRIPES
        val lock = locks[lockIndex]
        lock.lock()
        return try {
            val now = clock.instant()
            val fingerprint = command.fingerprint()
            val existing = idempotencyRecordRepository.findByUserIdAndKey(userId, key)
            if (existing != null && existing.expiresAt.isAfter(now)) {
                if (existing.requestFingerprint != fingerprint) {
                    throw IdempotencyConflictException(key)
                }
                existing.response
            } else {
                val todo = onCreate()
                idempotencyRecordRepository.save(
                    IdempotencyRecord(
                        userId = userId,
                        key = key,
                        requestFingerprint = fingerprint,
                        response = todo,
                        expiresAt = now.plus(RETENTION),
                    ),
                )
                todo
            }
        } finally {
            lock.unlock()
        }
    }

    private fun TodoCommand.fingerprint(): String {
        val fields = listOf(
            title,
            completed.toString(),
            description,
            dueDate?.toString(),
            priority?.name,
            estimatedMinutes?.toString(),
            tags.sorted().joinToString(",").ifEmpty { null },
        )
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            if (field == null) {
                digest.update(0.toByte())
            } else {
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                digest.update(1.toByte())
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest()
            .joinToString("") { byte ->
                (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
            }
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val HEX_RADIX = 16
        const val HEX_BYTE_WIDTH = 2
        const val LOCK_STRIPES = 64
        const val STRIPE_HASH_PRIME = 31
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
