package com.example.todo.application

import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class TodoServiceTest {
    private val repository = InMemoryTodoRepository()
    private val idempotencyRepository = InMemoryIdempotencyRecordRepository()
    private val eventPublisher = InMemoryTodoEventPublisher()
    private val userSettingsRepository = InMemoryUserSettingsRepository()
    private val service = TodoService(
        todoRepository = repository,
        idempotencyRecordRepository = idempotencyRepository,
        todoEventPublisher = eventPublisher,
        userSettingsRepository = userSettingsRepository,
    )
    private val userId: UUID = UUID.randomUUID()
    private val otherUserId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        eventPublisher.clear()
    }

    @Test
    fun `create trims title and returns generated id`() {
        val dueDate = LocalDate.parse("2025-04-18")
        val todo = service.create(
            userId,
            TodoCommand(
                title = "  Buy milk  ",
                description = "Buy before the weekend",
                dueDate = dueDate,
                priority = TodoPriority.HIGH,
                estimatedMinutes = 30,
            ),
        )

        assertEquals("Buy milk", todo.title)
        assertFalse(todo.completed)
        assertEquals("Buy before the weekend", todo.description)
        assertEquals(dueDate, todo.dueDate)
        assertEquals(TodoPriority.HIGH, todo.priority)
        assertEquals(30, todo.estimatedMinutes)
        assertEquals(userId, todo.userId)
        assertTrue(todo.id != null)
    }

    @Test
    fun `update changes title and completed state`() {
        val created = service.create(
            userId,
            TodoCommand(title = "Buy milk", dueDate = LocalDate.parse("2025-04-18")),
        )
        val dueDate = LocalDate.parse("2025-04-19")

        val updated = service.update(
            requireNotNull(created.id),
            userId,
            TodoCommand(
                title = "  Milk bought  ",
                completed = true,
                description = "Purchased",
                dueDate = dueDate,
                priority = TodoPriority.LOW,
                estimatedMinutes = 45,
            ),
        )

        assertEquals(created.id, updated.id)
        assertEquals("Milk bought", updated.title)
        assertTrue(updated.completed)
        assertEquals("Purchased", updated.description)
        assertEquals(dueDate, updated.dueDate)
        assertEquals(TodoPriority.LOW, updated.priority)
        assertEquals(45, updated.estimatedMinutes)
    }

    @Test
    fun `patch partially updates fields and keeps other fields`() {
        val created = service.create(
            userId,
            TodoCommand(
                title = "Initial Title",
                description = "Initial Description",
                dueDate = LocalDate.parse("2025-04-18"),
                priority = TodoPriority.LOW,
                estimatedMinutes = 15,
            ),
        )
        val todoId = requireNotNull(created.id)

        val patched = service.patch(
            todoId,
            userId,
            PatchTodoCommand(completed = true, priority = TodoPriority.HIGH),
        )

        assertEquals("Initial Title", patched.title)
        assertTrue(patched.completed)
        assertEquals("Initial Description", patched.description)
        assertEquals(LocalDate.parse("2025-04-18"), patched.dueDate)
        assertEquals(TodoPriority.HIGH, patched.priority)
        assertEquals(15, patched.estimatedMinutes)

        val event = eventPublisher.publishedEvents.last()
        assertEquals(TodoEventType.UPDATED, event.eventType)
        assertEquals(todoId, event.todoId)
    }

    @Test
    fun `patch reactivating completed todo rejects when active limit is reached`() {
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                maxActiveTodos = 1,
            ),
        )
        service.create(userId, TodoCommand(title = "Task 1"))
        val todo2 = service.create(userId, TodoCommand(title = "Task 2", completed = true))

        assertThrows(ActiveTodosLimitExceededException::class.java) {
            service.patch(
                requireNotNull(todo2.id),
                userId,
                PatchTodoCommand(completed = false),
            )
        }
    }

    @Test
    fun `list with completed and priority filters`() {
        service.create(userId, TodoCommand(title = "Task 1", completed = false, priority = TodoPriority.LOW))
        service.create(userId, TodoCommand(title = "Task 2", completed = true, priority = TodoPriority.HIGH))
        service.create(userId, TodoCommand(title = "Task 3", completed = false, priority = TodoPriority.HIGH))

        val completedHigh = service.list(userId, completed = true, priority = TodoPriority.HIGH)
        assertEquals(1, completedHigh.size)
        assertEquals("Task 2", completedHigh.first().title)

        val uncompleted = service.list(userId, completed = false)
        assertEquals(2, uncompleted.size)

        val highOnly = service.list(userId, priority = TodoPriority.HIGH)
        assertEquals(2, highOnly.size)
    }

    @Test
    fun `missing todo raises domain exception`() {
        assertThrows(TodoNotFoundException::class.java) {
            service.get(UUID.randomUUID(), userId)
        }
    }

    @Test
    fun `cannot access or modify another users todo`() {
        val created = service.create(userId, TodoCommand(title = "Private todo"))
        val todoId = requireNotNull(created.id)

        assertThrows(TodoNotFoundException::class.java) {
            service.get(todoId, otherUserId)
        }

        assertThrows(TodoNotFoundException::class.java) {
            service.update(todoId, otherUserId, TodoCommand(title = "Hacked"))
        }

        assertThrows(TodoNotFoundException::class.java) {
            service.delete(todoId, otherUserId)
        }
    }

    @Test
    fun `list only returns todos belonging to user`() {
        service.create(userId, TodoCommand(title = "User 1 todo"))
        service.create(otherUserId, TodoCommand(title = "User 2 todo"))

        val userTodos = service.list(userId)
        assertEquals(1, userTodos.size)
        assertEquals("User 1 todo", userTodos.first().title)

        val otherUserTodos = service.list(otherUserId)
        assertEquals(1, otherUserTodos.size)
        assertEquals("User 2 todo", otherUserTodos.first().title)
    }

    @Test
    fun `repeated idempotent create returns the original todo`() {
        val command = TodoCommand(title = "Buy milk")

        val created = service.createIdempotent(userId, "request-1", command)
        val replayed = service.createIdempotent(userId, "request-1", command)

        assertEquals(created, replayed)
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `reusing idempotency key for a different request raises conflict`() {
        service.createIdempotent(userId, "request-1", TodoCommand(title = "Buy milk"))

        assertThrows(IdempotencyConflictException::class.java) {
            service.createIdempotent(userId, "request-1", TodoCommand(title = "Buy bread"))
        }
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `expired idempotency key can create a new todo`() {
        val firstService = TodoService(
            repository,
            idempotencyRepository,
            eventPublisher,
            Clock.fixed(IDEMPOTENCY_TIME, ZoneOffset.UTC),
        )
        val secondService = TodoService(
            repository,
            idempotencyRepository,
            eventPublisher,
            Clock.fixed(IDEMPOTENCY_TIME.plus(Duration.ofHours(24)), ZoneOffset.UTC),
        )
        val command = TodoCommand(title = "Buy milk")

        val first = firstService.createIdempotent(userId, "request-1", command)
        val second = secondService.createIdempotent(userId, "request-1", command)

        assertTrue(first.id != second.id)
        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `create publishes CREATED event with todo payload`() {
        val todo = service.create(userId, TodoCommand(title = "Buy milk"))

        assertEquals(1, eventPublisher.publishedEvents.size)
        val event = eventPublisher.publishedEvents.first()
        assertEquals(TodoEventType.CREATED, event.eventType)
        assertEquals(todo.id, event.todoId)
        assertEquals(todo, event.todo)
    }

    @Test
    fun `update publishes UPDATED event with updated todo payload`() {
        val created = service.create(userId, TodoCommand(title = "Buy milk"))
        eventPublisher.clear()

        val updated = service.update(
            requireNotNull(created.id),
            userId,
            TodoCommand(title = "Milk bought", completed = true),
        )

        assertEquals(1, eventPublisher.publishedEvents.size)
        val event = eventPublisher.publishedEvents.first()
        assertEquals(TodoEventType.UPDATED, event.eventType)
        assertEquals(created.id, event.todoId)
        assertEquals(updated, event.todo)
    }

    @Test
    fun `delete publishes DELETED event with null payload`() {
        val created = service.create(userId, TodoCommand(title = "Buy milk"))
        eventPublisher.clear()

        service.delete(requireNotNull(created.id), userId)

        assertEquals(1, eventPublisher.publishedEvents.size)
        val event = eventPublisher.publishedEvents.first()
        assertEquals(TodoEventType.DELETED, event.eventType)
        assertEquals(created.id, event.todoId)
        assertEquals(null, event.todo)
    }

    @Test
    fun `replayed idempotent create does not publish duplicate event`() {
        val command = TodoCommand(title = "Buy milk")
        service.createIdempotent(userId, "request-1", command)
        assertEquals(1, eventPublisher.publishedEvents.size)

        eventPublisher.clear()
        service.createIdempotent(userId, "request-1", command)

        assertEquals(0, eventPublisher.publishedEvents.size)
    }

    private class InMemoryTodoRepository : TodoRepository {
        private val todos = mutableMapOf<UUID, Todo>()

        override fun findAll(): List<Todo> = todos.values.toList()

        override fun findAllByUserId(
            userId: UUID,
            completed: Boolean?,
            priority: TodoPriority?,
        ): List<Todo> =
            todos.values.filter {
                it.userId == userId &&
                    (completed == null || it.completed == completed) &&
                    (priority == null || it.priority == priority)
            }

        override fun findById(id: UUID): Todo? = todos[id]

        override fun findByIdAndUserId(id: UUID, userId: UUID): Todo? =
            todos[id]?.takeIf { it.userId == userId }

        override fun countByUserIdAndCompletedFalse(userId: UUID): Long =
            todos.values.count { it.userId == userId && !it.completed }.toLong()

        override fun count(): Long = todos.size.toLong()

        override fun countByCompleted(completed: Boolean): Long =
            todos.values.count { it.completed == completed }.toLong()

        override fun save(todo: Todo): Todo {
            val saved = todo.copy(id = todo.id ?: UUID.randomUUID())
            todos[requireNotNull(saved.id)] = saved
            return saved
        }

        override fun delete(todo: Todo) {
            todos.remove(requireNotNull(todo.id))
        }
    }

    private class InMemoryIdempotencyRecordRepository : IdempotencyRecordRepository {
        private val records = mutableMapOf<String, IdempotencyRecord>()

        override fun findByKey(key: String): IdempotencyRecord? = records[key]

        override fun save(record: IdempotencyRecord) {
            records[record.key] = record
        }

        override fun deleteExpiredBefore(instant: Instant) {
            records.entries.removeIf { (_, record) -> record.expiresAt <= instant }
        }
    }

    @Test
    fun `create applies user settings defaults when priority and estimatedMinutes are omitted`() {
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                defaultPriority = TodoPriority.HIGH,
                defaultEstimatedMinutes = 45,
            ),
        )
        val todo = service.create(userId, TodoCommand(title = "Default task"))
        assertEquals(TodoPriority.HIGH, todo.priority)
        assertEquals(45, todo.estimatedMinutes)
    }

    @Test
    fun `create rejects when active todos limit is reached`() {
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                maxActiveTodos = 2,
            ),
        )
        service.create(userId, TodoCommand(title = "Task 1"))
        service.create(userId, TodoCommand(title = "Task 2"))

        assertThrows(ActiveTodosLimitExceededException::class.java) {
            service.create(userId, TodoCommand(title = "Task 3"))
        }
    }

    @Test
    fun `update reactivating completed todo rejects when active limit is reached`() {
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                maxActiveTodos = 1,
            ),
        )
        service.create(userId, TodoCommand(title = "Task 1"))
        val todo2 = service.create(userId, TodoCommand(title = "Task 2", completed = true))

        assertThrows(ActiveTodosLimitExceededException::class.java) {
            service.update(
                requireNotNull(todo2.id),
                userId,
                TodoCommand(title = "Task 2", completed = false),
            )
        }
    }

    private class InMemoryUserSettingsRepository : UserSettingsRepository {
        private val storage = mutableMapOf<UUID, UserSettings>()

        override fun findByUserId(userId: UUID): UserSettings? = storage[userId]

        override fun save(userSettings: UserSettings): UserSettings {
            storage[userSettings.userId] = userSettings
            return userSettings
        }
    }

    private class InMemoryTodoEventPublisher : TodoEventPublisher {
        val publishedEvents = mutableListOf<TodoEvent>()

        override fun publish(event: TodoEvent) {
            publishedEvents.add(event)
        }

        fun clear() {
            publishedEvents.clear()
        }
    }

    private companion object {
        val IDEMPOTENCY_TIME: Instant = Instant.parse("2025-04-18T12:00:00Z")
    }
}
