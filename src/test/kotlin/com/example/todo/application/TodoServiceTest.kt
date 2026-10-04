package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class TodoServiceTest {
    private val repository = InMemoryTodoRepository()
    private val service = TodoService(repository)

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
}
