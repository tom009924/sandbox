package com.example.todo.application

import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
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
    val priority: TodoPriority = TodoPriority.MEDIUM,
    val estimatedMinutes: Int? = null,
)

class TodoService(
    private val todoRepository: TodoRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(): List<Todo> = todoRepository.findAll()

    fun get(id: UUID): Todo = findTodo(id)

    fun create(command: TodoCommand): Todo =
        todoRepository.save(
            Todo(
                title = command.title.trim(),
                description = command.description,
                dueDate = command.dueDate,
                priority = command.priority,
                estimatedMinutes = command.estimatedMinutes,
            ),
        )

    fun createIdempotent(key: String, command: TodoCommand): Todo {
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

        val todo = create(command)
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

    fun update(id: UUID, command: TodoCommand): Todo {
        val todo = findTodo(id)
        return todoRepository.save(
            todo.copy(
                title = command.title.trim(),
                completed = command.completed,
                description = command.description,
                dueDate = command.dueDate,
                priority = command.priority,
                estimatedMinutes = command.estimatedMinutes,
            ),
        )
    }

    fun delete(id: UUID) {
        todoRepository.delete(findTodo(id))
    }

    private fun findTodo(id: UUID): Todo =
        todoRepository.findById(id) ?: throw TodoNotFoundException(id)

    private fun TodoCommand.fingerprint(): String {
        val fields = listOf(
            title,
            completed.toString(),
            description,
            dueDate?.toString(),
            priority.name,
            estimatedMinutes?.toString(),
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
