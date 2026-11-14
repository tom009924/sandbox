package com.example.todo.application

import com.example.todo.domain.crypto.TodoCryptoHandler
import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserSettingsRepository
import java.time.Clock
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
    idempotencyRecordRepository: IdempotencyRecordRepository,
    private val todoEventPublisher: TodoEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val cryptoHandler: TodoCryptoHandler = TodoCryptoHandler(),
) {
    private val idempotencyHandler = TodoIdempotencyHandler(idempotencyRecordRepository, clock)
    fun list(
        userId: UUID,
        completed: Boolean? = null,
        priority: TodoPriority? = null,
        tag: String? = null,
    ): List<Todo> = todoRepository.findAllByUserId(userId, completed, priority, tag).map(cryptoHandler::decryptTodo)

    fun get(id: UUID, userId: UUID): Todo = cryptoHandler.decryptTodo(findTodo(id, userId))

    fun create(userId: UUID, command: TodoCommand): Todo {
        val settings = userSettingsRepository?.findByUserId(userId) ?: UserSettings.defaultFor(userId)
        ensureActiveTodoLimitNotExceeded(userId, checkRequired = !command.completed, settings = settings)
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
        publishEvent(TodoEventType.CREATED, requireNotNull(decrypted.id), decrypted)
        return decrypted
    }

    fun createIdempotent(userId: UUID, key: String, command: TodoCommand): Todo =
        idempotencyHandler.execute(userId, key, command) { create(userId, command) }

    fun update(id: UUID, userId: UUID, command: TodoCommand): Todo {
        val todo = findTodo(id, userId)
        ensureActiveTodoLimitNotExceeded(userId, checkRequired = todo.completed && !command.completed)
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
        publishEvent(TodoEventType.UPDATED, requireNotNull(decrypted.id), decrypted)
        return decrypted
    }

    fun patch(id: UUID, userId: UUID, command: PatchTodoCommand): Todo {
        val todo = findTodo(id, userId)
        val newCompleted = command.completed ?: todo.completed
        ensureActiveTodoLimitNotExceeded(userId, checkRequired = todo.completed && !newCompleted)
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
        publishEvent(TodoEventType.UPDATED, requireNotNull(decrypted.id), decrypted)
        return decrypted
    }

    fun delete(id: UUID, userId: UUID) {
        val todo = findTodo(id, userId)
        todoRepository.delete(todo)
        publishEvent(TodoEventType.DELETED, id, null)
    }

    private fun ensureActiveTodoLimitNotExceeded(
        userId: UUID,
        checkRequired: Boolean,
        settings: UserSettings? = null,
    ) {
        if (!checkRequired) return
        val effectiveSettings = settings
            ?: userSettingsRepository?.findByUserId(userId)
            ?: UserSettings.defaultFor(userId)
        val maxActive = effectiveSettings.maxActiveTodos ?: return
        val activeCount = todoRepository.countByUserIdAndCompletedFalse(userId)
        if (activeCount >= maxActive) {
            throw ActiveTodosLimitExceededException(maxActive)
        }
    }

    private fun publishEvent(eventType: TodoEventType, todoId: UUID, todo: Todo?) {
        todoEventPublisher.publish(
            TodoEvent(
                eventType = eventType,
                timestamp = clock.instant(),
                todoId = todoId,
                todo = todo,
            ),
        )
    }

    private fun findTodo(id: UUID, userId: UUID): Todo =
        todoRepository.findByIdAndUserId(id, userId) ?: throw TodoNotFoundException(id)
}
