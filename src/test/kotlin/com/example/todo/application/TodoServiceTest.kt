package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.TodoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TodoServiceTest {
    private val repository = InMemoryTodoRepository()
    private val service = TodoService(repository)

    @Test
    fun `create trims title and returns generated id`() {
        val todo = service.create("  Buy milk  ")

        assertEquals("Buy milk", todo.title)
        assertFalse(todo.completed)
        assertTrue(todo.id != null)
    }

    @Test
    fun `update changes title and completed state`() {
        val created = service.create("Buy milk")

        val updated = service.update(requireNotNull(created.id), "  Milk bought  ", true)

        assertEquals(created.id, updated.id)
        assertEquals("Milk bought", updated.title)
        assertTrue(updated.completed)
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
