package com.example.todo.application

import com.example.todo.domain.exception.LastProjectOwnerException
import com.example.todo.domain.exception.ProjectForbiddenException
import com.example.todo.domain.exception.ProjectMemberAlreadyExistsException
import com.example.todo.domain.exception.ProjectNotFoundException
import com.example.todo.domain.exception.UserNotFoundException
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.ProjectRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Suppress("TooManyFunctions")
class ProjectServiceTest {
    private val projectRepository = InMemoryProjectRepository()
    private val projectMemberRepository = InMemoryProjectMemberRepository()
    private val userRepository = InMemoryUserRepository()
    private val todoRepository = InMemoryTodoRepository()
    private val fixedClock = Clock.fixed(Instant.parse("2025-04-18T10:00:00Z"), ZoneOffset.UTC)
    private val service = ProjectService(
        projectRepository = projectRepository,
        projectMemberRepository = projectMemberRepository,
        userRepository = userRepository,
        todoRepository = todoRepository,
        clock = fixedClock,
    )

    private val ownerId = UUID.randomUUID()
    private val memberId = UUID.randomUUID()
    private val outsiderId = UUID.randomUUID()

    init {
        userRepository.save(User(id = ownerId, username = "alice", passwordHash = "hash"))
        userRepository.save(User(id = memberId, username = "bob", passwordHash = "hash"))
        userRepository.save(User(id = outsiderId, username = "charlie", passwordHash = "hash"))
    }

    @Test
    fun `create registers project and adds creator as owner`() {
        val project = service.create(ownerId, " Backend Migration ", "Move to Spanner")
        val projectId = requireNotNull(project.id)

        assertEquals("Backend Migration", project.name)
        assertEquals("Move to Spanner", project.description)
        assertEquals(ownerId, project.createdBy)

        val members = service.listMembers(projectId, ownerId)
        assertEquals(1, members.size)
        assertEquals(ProjectRole.OWNER, members.first().role)
        assertEquals("alice", members.first().username)
        assertEquals(1, service.listForUser(ownerId).size)
    }

    @Test
    fun `getForUser throws ProjectNotFoundException for non-member`() {
        val project = service.create(ownerId, "Shared Project", null)
        val projectId = requireNotNull(project.id)

        assertTrue(service.listForUser(outsiderId).isEmpty())
        assertThrows<ProjectNotFoundException> {
            service.getForUser(projectId, outsiderId)
        }
        assertThrows<ProjectNotFoundException> {
            service.listMembers(projectId, outsiderId)
        }
    }

    @Test
    fun `update allows owner and forbids member`() {
        val project = service.create(ownerId, "Initial", null)
        val projectId = requireNotNull(project.id)
        service.addMember(projectId, ownerId, "bob", ProjectRole.MEMBER)

        assertThrows<ProjectForbiddenException> {
            service.update(projectId, memberId, "Hacked", null)
        }

        val updated = service.update(projectId, ownerId, "Updated Name", "New desc")
        assertEquals("Updated Name", updated.name)
        assertEquals("New desc", updated.description)
    }

    @Test
    fun `delete removes project and its todos for owner`() {
        val project = service.create(ownerId, "To Delete", null)
        val projectId = requireNotNull(project.id)
        service.addMember(projectId, ownerId, "bob", ProjectRole.MEMBER)
        todoRepository.save(Todo(userId = ownerId, title = "Task 1", projectId = projectId))

        assertThrows<ProjectForbiddenException> {
            service.delete(projectId, memberId)
        }

        service.delete(projectId, ownerId)
        assertTrue(todoRepository.findAllByProjectId(projectId).isEmpty())
        assertThrows<ProjectNotFoundException> {
            service.getForUser(projectId, ownerId)
        }
    }

    @Test
    fun `addMember validates owner role, target user existence, and duplicates`() {
        val project = service.create(ownerId, "Team", null)
        val projectId = requireNotNull(project.id)

        val added = service.addMember(projectId, ownerId, "bob", ProjectRole.MEMBER)
        assertEquals(memberId, added.userId)
        assertEquals(ProjectRole.MEMBER, added.role)

        assertThrows<ProjectMemberAlreadyExistsException> {
            service.addMember(projectId, ownerId, "bob", ProjectRole.MEMBER)
        }
        assertThrows<UserNotFoundException> {
            service.addMember(projectId, ownerId, "unknown", ProjectRole.MEMBER)
        }
        assertThrows<ProjectForbiddenException> {
            service.addMember(projectId, memberId, "charlie", ProjectRole.MEMBER)
        }
    }

    @Test
    fun `removeMember prevents removing last owner and allows self leave`() {
        val project = service.create(ownerId, "Team", null)
        val projectId = requireNotNull(project.id)
        service.addMember(projectId, ownerId, "bob", ProjectRole.MEMBER)

        assertThrows<LastProjectOwnerException> {
            service.removeMember(projectId, ownerId, ownerId)
        }
        assertThrows<ProjectForbiddenException> {
            service.removeMember(projectId, memberId, ownerId)
        }

        service.removeMember(projectId, memberId, memberId)
        assertEquals(1, service.listMembers(projectId, ownerId).size)
    }

    private class InMemoryProjectRepository : ProjectRepository {
        private val storage = linkedMapOf<UUID, Project>()

        override fun save(project: Project): Project {
            val id = project.id ?: UUID.randomUUID()
            val saved = project.copy(id = id)
            storage[id] = saved
            return saved
        }

        override fun findById(id: UUID): Project? = storage[id]

        override fun findAllByIdIn(ids: Collection<UUID>): List<Project> =
            ids.mapNotNull { storage[it] }

        override fun deleteById(id: UUID) {
            storage.remove(id)
        }
    }

    private class InMemoryProjectMemberRepository : ProjectMemberRepository {
        private val storage = mutableListOf<ProjectMember>()

        override fun save(member: ProjectMember): ProjectMember {
            storage.removeIf { it.projectId == member.projectId && it.userId == member.userId }
            storage.add(member)
            return member
        }

        override fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember? =
            storage.find { it.projectId == projectId && it.userId == userId }

        override fun findAllByProjectId(projectId: UUID): List<ProjectMember> =
            storage.filter { it.projectId == projectId }

        override fun findAllByUserId(userId: UUID): List<ProjectMember> =
            storage.filter { it.userId == userId }

        override fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole): Long =
            storage.count { it.projectId == projectId && it.role == role }.toLong()

        override fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID) {
            storage.removeIf { it.projectId == projectId && it.userId == userId }
        }

        override fun deleteAllByProjectId(projectId: UUID) {
            storage.removeIf { it.projectId == projectId }
        }
    }

    private class InMemoryUserRepository : UserRepository {
        private val storage = linkedMapOf<UUID, User>()

        override fun findById(id: UUID): User? = storage[id]

        override fun findAll(): List<User> = storage.values.toList()

        override fun findByUsername(username: String): User? =
            storage.values.find { it.username == username }

        override fun existsByUsername(username: String): Boolean =
            storage.values.any { it.username == username }

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

    private class InMemoryTodoRepository : TodoRepository {
        private val storage = linkedMapOf<UUID, Todo>()

        override fun findAll(): List<Todo> = storage.values.toList()

        override fun findAllByUserId(
            userId: UUID,
            completed: Boolean?,
            priority: TodoPriority?,
            tag: String?,
        ): List<Todo> = storage.values.filter { it.userId == userId }

        override fun findAllByProjectId(
            projectId: UUID,
            completed: Boolean?,
            priority: TodoPriority?,
            tag: String?,
        ): List<Todo> = storage.values.filter { it.projectId == projectId }

        override fun findById(id: UUID): Todo? = storage[id]

        override fun findByIdAndUserId(id: UUID, userId: UUID): Todo? =
            storage.values.find { it.id == id && it.userId == userId }

        override fun countByUserIdAndCompletedFalse(userId: UUID): Long =
            storage.values.count { it.userId == userId && !it.completed }.toLong()

        override fun count(): Long = storage.size.toLong()

        override fun countByCompleted(completed: Boolean): Long =
            storage.values.count { it.completed == completed }.toLong()

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
}
