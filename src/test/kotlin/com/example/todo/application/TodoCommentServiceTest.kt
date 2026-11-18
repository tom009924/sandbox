package com.example.todo.application

import com.example.todo.domain.exception.TodoCommentForbiddenException
import com.example.todo.domain.exception.TodoCommentNotFoundException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoComment
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.TodoCommentRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TodoCommentServiceTest {
    private val todoRepository = InMemoryTodoRepository()
    private val commentRepository = InMemoryTodoCommentRepository()
    private val projectMemberRepository = InMemoryProjectMemberRepository()
    private val userRepository = InMemoryUserRepository()
    private val clock = Clock.fixed(Instant.parse("2025-04-18T12:00:00Z"), ZoneOffset.UTC)
    private val service = TodoCommentService(
        todoAccessChecker = TodoAccessChecker(todoRepository, projectMemberRepository),
        todoCommentRepository = commentRepository,
        userRepository = userRepository,
        clock = clock,
    )

    @Test
    fun `project members can add, list, and delete their own comments`() {
        val owner = userRepository.save(User(username = "alice", passwordHash = "h"))
        val member = userRepository.save(User(username = "bob", passwordHash = "h"))
        val outsider = userRepository.save(User(username = "eve", passwordHash = "h"))
        val ownerId = requireNotNull(owner.id)
        val memberId = requireNotNull(member.id)
        val outsiderId = requireNotNull(outsider.id)
        val projectId = UUID.randomUUID()
        projectMemberRepository.save(ProjectMember(projectId, ownerId, ProjectRole.OWNER, clock.instant()))
        projectMemberRepository.save(ProjectMember(projectId, memberId, ProjectRole.MEMBER, clock.instant()))
        val todo = todoRepository.save(Todo(userId = ownerId, title = "Shared", projectId = projectId))
        val todoId = requireNotNull(todo.id)

        val added = service.add(todoId, memberId, "  Looks good  ")
        assertEquals("Looks good", added.content)
        assertEquals("bob", added.username)

        val list = service.list(todoId, ownerId)
        assertEquals(1, list.size)

        assertThrows<TodoNotFoundException> {
            service.list(todoId, outsiderId)
        }
        assertThrows<TodoCommentForbiddenException> {
            service.delete(todoId, added.id, ownerId)
        }
        assertThrows<TodoCommentNotFoundException> {
            service.delete(todoId, UUID.randomUUID(), memberId)
        }

        service.delete(todoId, added.id, memberId)
        assertEquals(0, service.list(todoId, ownerId).size)
    }

    @Test
    fun `list deduplicates user lookups per distinct author`() {
        val alice = userRepository.save(User(username = "alice", passwordHash = "h"))
        val aliceId = requireNotNull(alice.id)
        val todo = todoRepository.save(Todo(userId = aliceId, title = "Task"))
        val todoId = requireNotNull(todo.id)

        service.add(todoId, aliceId, "First comment")
        service.add(todoId, aliceId, "Second comment")
        service.add(todoId, aliceId, "Third comment")
        userRepository.findByIdCalls = 0

        val comments = service.list(todoId, aliceId)

        assertEquals(3, comments.size)
        assertEquals(1, userRepository.findByIdCalls)
    }

    private class InMemoryTodoCommentRepository : TodoCommentRepository {
        private val storage = linkedMapOf<UUID, TodoComment>()

        override fun save(comment: TodoComment): TodoComment {
            val id = comment.id ?: UUID.randomUUID()
            val saved = comment.copy(id = id)
            storage[id] = saved
            return saved
        }

        override fun findAllByTodoIdOrderByCreatedAtAsc(todoId: UUID): List<TodoComment> =
            storage.values.filter { it.todoId == todoId }.sortedBy { it.createdAt }

        override fun findById(id: UUID): TodoComment? = storage[id]

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
        private val storage = mutableListOf<ProjectMember>()
        override fun save(member: ProjectMember): ProjectMember {
            storage.add(member)
            return member
        }
        override fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember? =
            storage.find { it.projectId == projectId && it.userId == userId }
        override fun findAllByProjectId(projectId: UUID) = storage.filter { it.projectId == projectId }
        override fun findAllByUserId(userId: UUID) = storage.filter { it.userId == userId }
        override fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole) = 0L
        override fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID) {
            storage.removeIf { it.projectId == projectId && it.userId == userId }
        }

        override fun deleteAllByProjectId(projectId: UUID) {
            storage.removeIf { it.projectId == projectId }
        }
    }

    private class InMemoryUserRepository : UserRepository {
        private val storage = linkedMapOf<UUID, User>()
        var findByIdCalls = 0
        override fun findById(id: UUID): User? {
            findByIdCalls++
            return storage[id]
        }
        override fun findAllByIdIn(ids: Collection<UUID>): List<User> =
            ids.mapNotNull { storage[it] }
        override fun findAll(): List<User> = storage.values.toList()
        override fun findByUsername(username: String): User? = storage.values.find { it.username == username }
        override fun existsByUsername(username: String): Boolean = false
        override fun save(user: User): User {
            val id = user.id ?: UUID.randomUUID()
            val saved = user.copy(id = id)
            storage[id] = saved
            return saved
        }
        override fun count(): Long = storage.size.toLong()
        override fun deleteAll() {
            storage.clear()
        }
    }
}
