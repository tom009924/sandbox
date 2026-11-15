package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.TodoSubtaskNotFoundException
import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.TodoSubtaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class TodoSubtaskServiceTest {
    private val todoRepository = InMemoryTodoRepository()
    private val subtaskRepository = InMemoryTodoSubtaskRepository()
    private val projectMemberRepository = InMemoryProjectMemberRepository()
    private val service = TodoSubtaskService(
        todoRepository = todoRepository,
        todoSubtaskRepository = subtaskRepository,
        projectMemberRepository = projectMemberRepository,
    )

    @Test
    fun `owner can add, list, patch, and delete subtasks`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val todo = todoRepository.save(Todo(userId = ownerId, title = "Shopping"))
        val todoId = requireNotNull(todo.id)

        val first = service.add(todoId, ownerId, "  Buy milk  ")
        val second = service.add(todoId, ownerId, "Buy eggs")
        assertEquals(0, first.position)
        assertEquals(1, second.position)
        assertEquals("Buy milk", first.title)

        val list = service.list(todoId, ownerId)
        assertEquals(listOf(first, second), list)

        val patched = service.patch(
            todoId = todoId,
            subtaskId = requireNotNull(first.id),
            userId = ownerId,
            title = "Buy oat milk",
            completed = true,
            position = 2,
        )
        assertEquals("Buy oat milk", patched.title)
        assertTrue(patched.completed)
        assertEquals(2, patched.position)

        assertThrows<TodoNotFoundException> {
            service.list(todoId, otherUserId)
        }
        assertThrows<TodoSubtaskNotFoundException> {
            service.delete(todoId, UUID.randomUUID(), ownerId)
        }

        service.delete(todoId, requireNotNull(first.id), ownerId)
        assertEquals(1, service.list(todoId, ownerId).size)
    }

    private class InMemoryTodoSubtaskRepository : TodoSubtaskRepository {
        private val storage = linkedMapOf<UUID, TodoSubtask>()

        override fun save(subtask: TodoSubtask): TodoSubtask {
            val id = subtask.id ?: UUID.randomUUID()
            val saved = subtask.copy(id = id)
            storage[id] = saved
            return saved
        }

        override fun findAllByTodoIdOrderByPositionAsc(todoId: UUID): List<TodoSubtask> =
            storage.values.filter { it.todoId == todoId }.sortedBy { it.position }

        override fun findById(id: UUID): TodoSubtask? = storage[id]

        override fun deleteById(id: UUID) {
            storage.remove(id)
        }

        override fun deleteAllByTodoId(todoId: UUID) {
            storage.entries.removeIf { it.value.todoId == todoId }
        }
    }

    private class InMemoryTodoRepository : TodoRepository {
        private val storage = linkedMapOf<UUID, Todo>()

        override fun findAll(): List<Todo> = storage.values.toList()
        override fun findAllByUserId(userId: UUID, completed: Boolean?, priority: TodoPriority?, tag: String?) =
            emptyList<Todo>()
        override fun findAllByProjectId(projectId: UUID, completed: Boolean?, priority: TodoPriority?, tag: String?) =
            emptyList<Todo>()
        override fun findById(id: UUID): Todo? = storage[id]
        override fun findByIdAndUserId(id: UUID, userId: UUID): Todo? = null
        override fun countByUserIdAndCompletedFalse(userId: UUID): Long = 0
        override fun count(): Long = storage.size.toLong()
        override fun countByCompleted(completed: Boolean): Long = 0
        override fun save(todo: Todo): Todo {
            val id = todo.id ?: UUID.randomUUID()
            val saved = todo.copy(id = id)
            storage[id] = saved
            return saved
        }
        override fun delete(todo: Todo) {
            storage.remove(todo.id)
        }
        override fun deleteAllByProjectId(projectId: UUID) {
            storage.entries.removeIf { it.value.projectId == projectId }
        }
    }

    private class InMemoryProjectMemberRepository : ProjectMemberRepository {
        override fun save(member: ProjectMember) = member
        override fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember? = null
        override fun findAllByProjectId(projectId: UUID) = emptyList<ProjectMember>()
        override fun findAllByUserId(userId: UUID) = emptyList<ProjectMember>()
        override fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole) = 0L
        override fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID) {
            // No-op for unit test stub
        }
        override fun deleteAllByProjectId(projectId: UUID) {
            // No-op for unit test stub
        }
    }
}
