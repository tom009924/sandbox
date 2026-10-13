package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.model.IdempotencyRecord
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    private val service = TodoService(repository, idempotencyRepository)

    @Test
    fun `create trims title and returns generated id`() {
        val dueDate = LocalDate.parse("2025-04-18")
        val todo = service.create(
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
        assertTrue(todo.id != null)
    }

    @Test
    fun `update changes title and completed state`() {
        val created = service.create(
            TodoCommand(title = "Buy milk", dueDate = LocalDate.parse("2025-04-18")),
        )
        val dueDate = LocalDate.parse("2025-04-19")

        val updated = service.update(
            requireNotNull(created.id),
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
    fun `missing todo raises domain exception`() {
        assertThrows(TodoNotFoundException::class.java) {
            service.get(UUID.randomUUID())
        }
    }

    @Test
    fun `repeated idempotent create returns the original todo`() {
        val command = TodoCommand(title = "Buy milk")

        val created = service.createIdempotent("request-1", command)
        val replayed = service.createIdempotent("request-1", command)

        assertEquals(created, replayed)
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `reusing idempotency key for a different request raises conflict`() {
        service.createIdempotent("request-1", TodoCommand(title = "Buy milk"))

        assertThrows(IdempotencyConflictException::class.java) {
            service.createIdempotent("request-1", TodoCommand(title = "Buy bread"))
        }
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `expired idempotency key can create a new todo`() {
        val firstService = TodoService(
            repository,
            idempotencyRepository,
            Clock.fixed(IDEMPOTENCY_TIME, ZoneOffset.UTC),
        )
        val secondService = TodoService(
            repository,
            idempotencyRepository,
            Clock.fixed(IDEMPOTENCY_TIME.plus(Duration.ofHours(24)), ZoneOffset.UTC),
        )
        val command = TodoCommand(title = "Buy milk")

        val first = firstService.createIdempotent("request-1", command)
        val second = secondService.createIdempotent("request-1", command)

        assertTrue(first.id != second.id)
        assertEquals(2, repository.findAll().size)
    }

    private class InMemoryTodoRepository : TodoRepository {
        private val todos = mutableMapOf<UUID, Todo>()

        override fun findAll(): List<Todo> = todos.values.toList()

        override fun findById(id: UUID): Todo? = todos[id]

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

    private companion object {
        val IDEMPOTENCY_TIME: Instant = Instant.parse("2025-04-18T12:00:00Z")
    }
}
