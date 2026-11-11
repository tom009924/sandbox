package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
class TodoRepositoryAdapterTest @Autowired constructor(
    private val adapter: TodoRepositoryAdapter,
    private val springDataTodoRepository: SpringDataTodoRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataTodoRepository.deleteAll()
    }

    @Test
    fun `save inserts new entity and updates existing entity`() {
        val userId = UUID.randomUUID()
        val newTodo = Todo(
            id = null,
            userId = userId,
            title = "Test Todo",
            priority = TodoPriority.HIGH,
            dueDate = LocalDate.now().plusDays(1),
            estimatedMinutes = 45,
        )

        val inserted = adapter.save(newTodo)
        assertNotNull(inserted.id)
        assertEquals("Test Todo", inserted.title)

        val updated = adapter.save(inserted.copy(title = "Updated Title", completed = true))
        assertEquals(inserted.id, updated.id)
        assertEquals("Updated Title", updated.title)
        assertTrue(updated.completed)
    }

    @Test
    fun `findById and findByIdAndUserId find entity properly`() {
        val userId = UUID.randomUUID()
        val saved = adapter.save(
            Todo(id = null, userId = userId, title = "Find Me", priority = TodoPriority.MEDIUM),
        )
        val todoId = requireNotNull(saved.id)

        val byId = adapter.findById(todoId)
        assertNotNull(byId)
        assertEquals(todoId, byId?.id)

        val byIdAndUserId = adapter.findByIdAndUserId(todoId, userId)
        assertNotNull(byIdAndUserId)
        assertEquals(todoId, byIdAndUserId?.id)

        assertNull(adapter.findByIdAndUserId(todoId, UUID.randomUUID()))
    }

    @Test
    fun `findAllByUserId and count queries execute properly`() {
        val userId = UUID.randomUUID()
        val todo1 = adapter.save(
            Todo(id = null, userId = userId, title = "Active High", priority = TodoPriority.HIGH, completed = false),
        )
        val todo2 = adapter.save(
            Todo(id = null, userId = userId, title = "Done High", priority = TodoPriority.HIGH, completed = true),
        )

        val allForUser = adapter.findAllByUserId(userId, null, null)
        assertEquals(2, allForUser.size)

        val activeHigh = adapter.findAllByUserId(userId, completed = false, priority = TodoPriority.HIGH)
        assertEquals(1, activeHigh.size)
        assertEquals(todo1.id, activeHigh.first().id)

        val countUncompleted = adapter.countByUserIdAndCompletedFalse(userId)
        assertEquals(1L, countUncompleted)

        val countCompleted = adapter.countByCompleted(true)
        assertTrue(countCompleted >= 1L)

        adapter.delete(todo2)
        assertEquals(1, adapter.findAllByUserId(userId, null, null).size)
    }
}
