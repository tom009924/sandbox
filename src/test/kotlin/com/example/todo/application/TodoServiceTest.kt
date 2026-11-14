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
import org.junit.jupiter.api.Assertions.assertNotEquals
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
                tags = listOf("shopping", "dairy"),
            ),
        )

        assertEquals("Buy milk", todo.title)
        assertFalse(todo.completed)
        assertEquals("Buy before the weekend", todo.description)
        assertEquals(dueDate, todo.dueDate)
        assertEquals(TodoPriority.HIGH, todo.priority)
        assertEquals(30, todo.estimatedMinutes)
        assertEquals(listOf("shopping", "dairy"), todo.tags)
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
    fun `list with completed and priority and tag filters`() {
        service.create(
            userId,
            TodoCommand(title = "Task 1", completed = false, priority = TodoPriority.LOW, tags = listOf("work")),
        )
        service.create(
            userId,
            TodoCommand(title = "Task 2", completed = true, priority = TodoPriority.HIGH, tags = listOf("home")),
        )
        service.create(
            userId,
            TodoCommand(title = "Task 3", completed = false, priority = TodoPriority.HIGH, tags = listOf("work")),
        )

        val completedHigh = service.list(userId, completed = true, priority = TodoPriority.HIGH)
        assertEquals(1, completedHigh.size)
        assertEquals("Task 2", completedHigh.first().title)

        val uncompleted = service.list(userId, completed = false)
        assertEquals(2, uncompleted.size)

        val highOnly = service.list(userId, priority = TodoPriority.HIGH)
        assertEquals(2, highOnly.size)

        val workOnly = service.list(userId, tag = "work")
        assertEquals(2, workOnly.size)

        val workUncompleted = service.list(userId, completed = false, tag = "work")
        assertEquals(2, workUncompleted.size)

        val homeCompletedHigh = service.list(userId, completed = true, priority = TodoPriority.HIGH, tag = "home")
        assertEquals(1, homeCompletedHigh.size)
        assertEquals("Task 2", homeCompletedHigh.first().title)
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
            tag: String?,
        ): List<Todo> =
            todos.values.filter {
                it.userId == userId &&
                    (completed == null || it.completed == completed) &&
                    (priority == null || it.priority == priority) &&
                    (tag == null || it.tags.contains(tag))
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
        private val records = mutableMapOf<Pair<UUID, String>, IdempotencyRecord>()

        override fun findByUserIdAndKey(userId: UUID, key: String): IdempotencyRecord? =
            records[userId to key]

        override fun save(record: IdempotencyRecord) {
            records[record.userId to record.key] = record
        }

        override fun deleteExpiredBefore(instant: Instant) {
            records.entries.removeIf { (_, record) -> record.expiresAt <= instant }
        }
    }

    @Test
    fun `createIdempotent isolates same key across different users`() {
        val otherUserId = UUID.randomUUID()
        val command = TodoCommand(title = "Buy milk")

        val firstTodo = service.createIdempotent(userId, "shared-key", command)
        val secondTodo = service.createIdempotent(otherUserId, "shared-key", command)

        assertEquals(userId, firstTodo.userId)
        assertEquals(otherUserId, secondTodo.userId)
        assertNotEquals(firstTodo.id, secondTodo.id)
    }

    @Test
    fun `createIdempotent serializes concurrent requests with same key without duplicate creation`() {
        val command = TodoCommand(title = "Concurrent milk")
        val threadCount = 8
        val latch = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (1..threadCount).map {
                pool.submit<Todo> {
                    latch.await()
                    service.createIdempotent(userId, "concurrent-key", command)
                }
            }
            latch.countDown()
            val results = futures.map { it.get() }
            assertEquals(1, results.map { it.id }.toSet().size)
            assertEquals(1, repository.findAll().size)
        } finally {
            pool.shutdownNow()
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

    @Test
    fun `create with cryptoService encrypts description in repository and returns plaintext`() {
        val fakeCrypto = FakeCryptoService()
        val handler = com.example.todo.domain.crypto.TodoCryptoHandler(fakeCrypto, "projects/todo/keys/test-key")
        val encryptedService = TodoService(
            todoRepository = repository,
            idempotencyRecordRepository = idempotencyRepository,
            todoEventPublisher = eventPublisher,
            cryptoHandler = handler,
        )

        val created = encryptedService.create(
            userId,
            TodoCommand(title = "Secret Task", description = "Confidential Details"),
        )

        // API 戻り値は復号化された平文
        assertEquals("Confidential Details", created.description)

        // リポジトリに保存された実データは暗号化されている
        val stored = repository.findByIdAndUserId(requireNotNull(created.id), userId)
        assertTrue(requireNotNull(stored?.description).startsWith("enc:"))

        // get も平文で返る
        val fetched = encryptedService.get(requireNotNull(created.id), userId)
        assertEquals("Confidential Details", fetched.description)
    }

    @Test
    fun `update and patch with cryptoService re-encrypts description in repository`() {
        val fakeCrypto = FakeCryptoService()
        val handler = com.example.todo.domain.crypto.TodoCryptoHandler(fakeCrypto, "projects/todo/keys/test-key")
        val encryptedService = TodoService(
            todoRepository = repository,
            idempotencyRecordRepository = idempotencyRepository,
            todoEventPublisher = eventPublisher,
            cryptoHandler = handler,
        )

        val created = encryptedService.create(
            userId,
            TodoCommand(title = "Task", description = "Original Secret"),
        )
        val id = requireNotNull(created.id)

        val updated = encryptedService.update(
            id,
            userId,
            TodoCommand(title = "Task", description = "Updated Secret"),
        )
        assertEquals("Updated Secret", updated.description)

        val patched = encryptedService.patch(
            id,
            userId,
            PatchTodoCommand(description = "Patched Secret"),
        )
        assertEquals("Patched Secret", patched.description)
    }

    private class FakeCryptoService : com.example.todo.domain.crypto.CryptoService {
        override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray =
            "ENCRYPTED_${String(plaintext, java.nio.charset.StandardCharsets.UTF_8)}".toByteArray()

        override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray {
            val str = String(ciphertext, java.nio.charset.StandardCharsets.UTF_8)
            return str.removePrefix("ENCRYPTED_").toByteArray()
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
