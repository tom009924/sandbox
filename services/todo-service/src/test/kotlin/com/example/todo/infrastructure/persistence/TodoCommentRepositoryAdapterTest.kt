package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["identity.service.enabled=false"])
class TodoCommentRepositoryAdapterTest @Autowired constructor(
    private val adapter: TodoCommentRepositoryAdapter,
    private val springDataTodoCommentRepository: SpringDataTodoCommentRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataTodoCommentRepository.deleteAll()
    }

    @Test
    fun `save, find, and delete todo comments`() {
        val todoId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val t1 = Instant.parse("2025-04-18T10:00:00Z")
        val t2 = Instant.parse("2025-04-18T10:05:00Z")

        val second = adapter.save(TodoComment(todoId = todoId, userId = userId, content = "Second", createdAt = t2))
        val first = adapter.save(TodoComment(todoId = todoId, userId = userId, content = "First", createdAt = t1))
        val firstId = requireNotNull(first.id)

        assertNotNull(adapter.findById(firstId))
        val ordered = adapter.findAllByTodoIdOrderByCreatedAtAsc(todoId)
        assertEquals(listOf("First", "Second"), ordered.map { it.content })

        adapter.deleteById(firstId)
        assertNull(adapter.findById(firstId))

        adapter.deleteAllByTodoId(todoId)
        assertEquals(0, adapter.findAllByTodoIdOrderByCreatedAtAsc(todoId).size)
        assertNotNull(second.id)
    }
}
