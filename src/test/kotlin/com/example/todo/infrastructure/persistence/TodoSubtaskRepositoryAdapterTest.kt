package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoSubtask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest
class TodoSubtaskRepositoryAdapterTest @Autowired constructor(
    private val adapter: TodoSubtaskRepositoryAdapter,
    private val springDataTodoSubtaskRepository: SpringDataTodoSubtaskRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataTodoSubtaskRepository.deleteAll()
    }

    @Test
    fun `save, find, update, and delete todo subtasks`() {
        val todoId = UUID.randomUUID()
        val second = adapter.save(TodoSubtask(todoId = todoId, title = "Step 2", position = 1))
        val first = adapter.save(TodoSubtask(todoId = todoId, title = "Step 1", position = 0))
        val firstId = requireNotNull(first.id)

        assertNotNull(adapter.findById(firstId))
        assertEquals(listOf("Step 1", "Step 2"), adapter.findAllByTodoIdOrderByPositionAsc(todoId).map { it.title })

        val updated = adapter.save(first.copy(completed = true))
        assertTrue(updated.completed)

        adapter.deleteById(firstId)
        assertNull(adapter.findById(firstId))

        adapter.deleteAllByTodoId(todoId)
        assertEquals(0, adapter.findAllByTodoIdOrderByPositionAsc(todoId).size)
        assertNotNull(second.id)
    }
}
