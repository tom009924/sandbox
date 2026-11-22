package com.example.todo.application

import com.example.todo.domain.decision.ProjectMemberRemovalDecisionInput
import com.example.todo.domain.decision.TodoDecisionModel
import com.example.todo.domain.exception.LastProjectOwnerException
import com.example.todo.domain.exception.ProjectForbiddenException
import com.example.todo.domain.exception.ProjectMemberAlreadyExistsException
import com.example.todo.domain.exception.ProjectMemberNotFoundException
import com.example.todo.domain.exception.ProjectNotFoundException
import com.example.todo.domain.exception.UserNotFoundException
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.ProjectRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ProjectMemberInfo(
    val projectId: UUID,
    val userId: UUID,
    val username: String,
    val role: ProjectRole,
    val joinedAt: Instant,
)

@Suppress("TooManyFunctions")
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val userRepository: UserRepository,
    private val todoRepository: TodoRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(userId: UUID, name: String, description: String?): Project {
        val now = clock.instant()
        val saved = projectRepository.save(
            Project(
                name = name.trim(),
                description = description,
                createdBy = userId,
                createdAt = now,
            ),
        )
        val projectId = requireNotNull(saved.id)
        projectMemberRepository.save(
            ProjectMember(
                projectId = projectId,
                userId = userId,
                role = ProjectRole.OWNER,
                joinedAt = now,
            ),
        )
        return saved
    }

    fun listForUser(userId: UUID): List<Project> {
        val projectIds = projectMemberRepository.findAllByUserId(userId).map { it.projectId }
        if (projectIds.isEmpty()) return emptyList()
        return projectRepository.findAllByIdIn(projectIds)
    }

    fun getForUser(projectId: UUID, userId: UUID): Project {
        requireMembership(projectId, userId)
        return projectRepository.findById(projectId) ?: throw ProjectNotFoundException(projectId)
    }

    fun update(projectId: UUID, userId: UUID, name: String, description: String?): Project {
        val member = requireMembership(projectId, userId)
        if (member.role != ProjectRole.OWNER) {
            throw ProjectForbiddenException("Only project owners can update the project")
        }
        val existing = projectRepository.findById(projectId) ?: throw ProjectNotFoundException(projectId)
        return projectRepository.save(
            existing.copy(
                name = name.trim(),
                description = description,
            ),
        )
    }

    fun delete(projectId: UUID, userId: UUID) {
        requireOwner(projectId, userId, "Only project owners can delete the project")
        if (projectRepository.findById(projectId) == null) {
            throw ProjectNotFoundException(projectId)
        }
        todoRepository.deleteAllByProjectId(projectId)
        projectMemberRepository.deleteAllByProjectId(projectId)
        projectRepository.deleteById(projectId)
    }

    fun listMembers(projectId: UUID, userId: UUID): List<ProjectMemberInfo> {
        requireMembership(projectId, userId)
        val members = projectMemberRepository.findAllByProjectId(projectId)
        val usersById = userRepository.findAllByIdIn(members.map { it.userId }.distinct())
            .associateBy { requireNotNull(it.id) }
        return members.map { member ->
            val username = usersById[member.userId]?.username ?: member.userId.toString()
            ProjectMemberInfo(
                projectId = member.projectId,
                userId = member.userId,
                username = username,
                role = member.role,
                joinedAt = member.joinedAt,
            )
        }
    }

    fun addMember(
        projectId: UUID,
        actorUserId: UUID,
        username: String,
        role: ProjectRole = ProjectRole.MEMBER,
    ): ProjectMemberInfo {
        requireOwner(projectId, actorUserId, "Only project owners can add members")
        val targetUser = userRepository.findByUsername(username.trim())
            ?: throw UserNotFoundException(username.trim())
        val targetUserId = requireNotNull(targetUser.id)
        if (projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId) != null) {
            throw ProjectMemberAlreadyExistsException(projectId, targetUserId)
        }
        val saved = projectMemberRepository.save(
            ProjectMember(
                projectId = projectId,
                userId = targetUserId,
                role = role,
                joinedAt = clock.instant(),
            ),
        )
        return ProjectMemberInfo(
            projectId = saved.projectId,
            userId = saved.userId,
            username = targetUser.username,
            role = saved.role,
            joinedAt = saved.joinedAt,
        )
    }

    fun removeMember(projectId: UUID, actorUserId: UUID, targetUserId: UUID) {
        val actor = requireMembership(projectId, actorUserId)
        val isSelfRemoval = actorUserId == targetUserId
        val target = when {
            isSelfRemoval -> actor
            actor.role != ProjectRole.OWNER -> null
            else -> projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId)
        }
        val isLastOwner = target?.role == ProjectRole.OWNER &&
            projectMemberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER) <= 1
        val result = TodoDecisionModel.evaluateProjectMemberRemovalDecision(
            ProjectMemberRemovalDecisionInput(
                isSelfRemoval = isSelfRemoval,
                actorRole = actor.role.name,
                targetExists = target != null,
                targetRole = target?.role?.name ?: "NONE",
                isLastOwner = isLastOwner,
            ),
        )
        val denialException: RuntimeException? = when (result.decision) {
            "DENY_FORBIDDEN" -> ProjectForbiddenException("Only project owners can remove other members")
            "DENY_NOT_FOUND" -> ProjectMemberNotFoundException(projectId, targetUserId)
            "DENY_LAST_OWNER" -> LastProjectOwnerException(projectId)
            else -> null
        }
        if (denialException != null) {
            throw denialException
        }
        projectMemberRepository.deleteByProjectIdAndUserId(projectId, targetUserId)
    }

    private fun requireOwner(projectId: UUID, userId: UUID, message: String): ProjectMember {
        val member = requireMembership(projectId, userId)
        if (member.role != ProjectRole.OWNER) {
            throw ProjectForbiddenException(message)
        }
        return member
    }

    private fun requireMembership(projectId: UUID, userId: UUID): ProjectMember =
        projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
            ?: throw ProjectNotFoundException(projectId)
}
