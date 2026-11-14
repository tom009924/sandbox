package com.example.todo.application

import com.example.todo.domain.crypto.TodoCryptoHandler
import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserSettingsRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class TodoCommand(
    val title: String,
    val completed: Boolean = false,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority? = null,
    val estimatedMinutes: Int? = null,
    val tags: List<String> = emptyList(),
)

data class PatchTodoCommand(
    val title: String? = null,
    val completed: Boolean? = null,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority? = null,
    val estimatedMinutes: Int? = null,
    val tags: List<String>? = null,
)

class TodoService(
    private val todoRepository: TodoRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val todoEventPublisher: TodoEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val cryptoHandler: TodoCryptoHandler = TodoCryptoHandler(),
) {
    fun list(
        userId: UUID,
        completed: Boolean? = null,
        priority: TodoPriority? = null,
        tag: String? = null,
    ): List<Todo> = todoRepository.findAllByUserId(userId, completed, priority, tag).map(cryptoHandler::decryptTodo)

    fun get(id: UUID, userId: UUID): Todo = cryptoHandler.decryptTodo(findTodo(id, userId))

    fun create(userId: UUID, command: TodoCommand): Todo {
        val settings = userSettingsRepository?.findByUserId(userId) ?: UserSettings.defaultFor(userId)
        if (!command.completed) {
            val maxActive = settings.maxActiveTodos
            if (maxActive != null) {
                val activeCount = todoRepository.countByUserIdAndCompletedFalse(userId)
                if (activeCount >= maxActive) {
                    throw ActiveTodosLimitExceededException(maxActive)
                }
            }
        }
        val effectivePriority = command.priority ?: settings.defaultPriority
        val effectiveEstimatedMinutes = command.estimatedMinutes ?: settings.defaultEstimatedMinutes
        val saved = todoRepository.save(
            Todo(
                userId = userId,
                title = command.title.trim(),
                completed = command.completed,
                description = cryptoHandler.encrypt(command.description),
                dueDate = command.dueDate,
                priority = effectivePriority,
                estimatedMinutes = effectiveEstimatedMinutes,
                tags = command.tags,
            ),
        )
        val decrypted = cryptoHandler.decryptTodo(saved)
        todoEventPublisher.publish(
            TodoEvent(
                eventType = TodoEventType.CREATED,
                timestamp = clock.instant(),
                todoId = requireNotNull(decrypted.id),
                todo = decrypted,
            ),
        )
        return decrypted
    }

    fun createIdempotent(userId: UUID, key: String, command: TodoCommand): Todo {
        val now = clock.instant()
        idempotencyRecordRepository.deleteExpiredBefore(now)
        val fingerprint = command.fingerprint()
        val existing = idempotencyRecordRepository.findByKey(key)
        if (existing != null) {
            if (existing.requestFingerprint != fingerprint) {
                throw IdempotencyConflictException(key)
            }
            return existing.response
        }

        val todo = create(userId, command)
        idempotencyRecordRepository.save(
            IdempotencyRecord(
                key = key,
                requestFingerprint = fingerprint,
                response = todo,
                expiresAt = now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
        return todo
    }

    fun update(id: UUID, userId: UUID, command: TodoCommand): Todo {
        val todo = findTodo(id, userId)
        checkActiveLimitOnReopen(userId, wasCompleted = todo.completed, nowCompleted = command.completed)
        val updated = todoRepository.save(
            todo.copy(
                title = command.title.trim(),
                completed = command.completed,
                description = cryptoHandler.encrypt(command.description),
                dueDate = command.dueDate,
                priority = command.priority ?: todo.priority,
                estimatedMinutes = command.estimatedMinutes,
                tags = command.tags,
            ),
        )
        val decrypted = cryptoHandler.decryptTodo(updated)
        todoEventPublisher.publish(
            TodoEvent(
                eventType = TodoEventType.UPDATED,
                timestamp = clock.instant(),
                todoId = requireNotNull(decrypted.id),
                todo = decrypted,
            ),
        )
        return decrypted
    }

    fun patch(id: UUID, userId: UUID, command: PatchTodoCommand): Todo {
        val todo = findTodo(id, userId)
        val newCompleted = command.completed ?: todo.completed
        checkActiveLimitOnReopen(userId, wasCompleted = todo.completed, nowCompleted = newCompleted)
        val newDescription = command.description?.let { cryptoHandler.encrypt(it) } ?: todo.description
        val updated = todoRepository.save(
            todo.copy(
                title = command.title?.trim() ?: todo.title,
                completed = newCompleted,
                description = newDescription,
                dueDate = command.dueDate ?: todo.dueDate,
                priority = command.priority ?: todo.priority,
                estimatedMinutes = command.estimatedMinutes ?: todo.estimatedMinutes,
                tags = command.tags ?: todo.tags,
            ),
        )
        val decrypted = cryptoHandler.decryptTodo(updated)
        todoEventPublisher.publish(
            TodoEvent(
                eventType = TodoEventType.UPDATED,
                timestamp = clock.instant(),
                todoId = requireNotNull(decrypted.id),
                todo = decrypted,
            ),
        )
        return decrypted
    }

    private fun checkActiveLimitOnReopen(userId: UUID, wasCompleted: Boolean, nowCompleted: Boolean) {
        if (!nowCompleted && wasCompleted) {
            val settings = userSettingsRepository?.findByUserId(userId) ?: UserSettings.defaultFor(userId)
            val maxActive = settings.maxActiveTodos
            if (maxActive != null) {
                val activeCount = todoRepository.countByUserIdAndCompletedFalse(userId)
                if (activeCount >= maxActive) {
                    throw ActiveTodosLimitExceededException(maxActive)
                }
            }
        }
    }

    fun delete(id: UUID, userId: UUID) {
        val todo = findTodo(id, userId)
        todoRepository.delete(todo)
        todoEventPublisher.publish(
            TodoEvent(
                eventType = TodoEventType.DELETED,
                timestamp = clock.instant(),
                todoId = id,
                todo = null,
            ),
        )
    }

    private fun findTodo(id: UUID, userId: UUID): Todo =
        todoRepository.findByIdAndUserId(id, userId) ?: throw TodoNotFoundException(id)

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
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofHours(24)
    }
}
