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
import java.util.concurrent.locks.ReentrantLock

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

enum class TodoSortField {
    DUE_DATE,
    PRIORITY,
    TITLE,
    ;

    fun comparator(sortOrder: SortOrder): Comparator<Todo> = when (this) {
        DUE_DATE -> if (sortOrder == SortOrder.ASC) {
            compareBy(nullsLast()) { it.dueDate }
        } else {
            compareBy(nullsLast(reverseOrder())) { it.dueDate }
        }
        PRIORITY -> if (sortOrder == SortOrder.ASC) {
            compareBy { it.priority.ordinal }
        } else {
            compareByDescending { it.priority.ordinal }
        }
        TITLE -> if (sortOrder == SortOrder.ASC) {
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        } else {
            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
    }
}

enum class SortOrder {
    ASC,
    DESC,
}

data class TodoListQuery(
    val completed: Boolean? = null,
    val priority: TodoPriority? = null,
    val tag: String? = null,
    val keyword: String? = null,
    val dueBefore: LocalDate? = null,
    val dueAfter: LocalDate? = null,
    val sortBy: TodoSortField? = null,
    val sortOrder: SortOrder = SortOrder.ASC,
)

class TodoService(
    private val todoRepository: TodoRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val todoEventPublisher: TodoEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val cryptoHandler: TodoCryptoHandler = TodoCryptoHandler(),
) {
    private val idempotencyLocks = Array(IDEMPOTENCY_LOCK_STRIPES) { ReentrantLock() }

    fun list(userId: UUID, query: TodoListQuery = TodoListQuery()): List<Todo> {
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val filtered = todoRepository
            .findAllByUserId(userId, query.completed, query.priority, query.tag)
            .map(cryptoHandler::decryptTodo)
            .filter { todo ->
                (keyword == null || todo.title.contains(keyword, ignoreCase = true)) &&
                    (query.dueAfter == null || (todo.dueDate != null && !todo.dueDate.isBefore(query.dueAfter))) &&
                    (query.dueBefore == null || (todo.dueDate != null && !todo.dueDate.isAfter(query.dueBefore)))
            }
        val sortBy = query.sortBy ?: return filtered
        return filtered.sortedWith(sortBy.comparator(query.sortOrder))
    }

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
        val lockIndex = (((userId.hashCode() * STRIPE_HASH_PRIME) + key.hashCode()) and Int.MAX_VALUE) %
            IDEMPOTENCY_LOCK_STRIPES
        val lock = idempotencyLocks[lockIndex]
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
                val todo = create(userId, command)
                idempotencyRecordRepository.save(
                    IdempotencyRecord(
                        userId = userId,
                        key = key,
                        requestFingerprint = fingerprint,
                        response = todo,
                        expiresAt = now.plus(IDEMPOTENCY_RETENTION),
                    ),
                )
                todo
            }
        } finally {
            lock.unlock()
        }
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
        const val IDEMPOTENCY_LOCK_STRIPES = 64
        const val STRIPE_HASH_PRIME = 31
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofHours(24)
    }
}
