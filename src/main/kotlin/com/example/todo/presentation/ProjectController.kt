package com.example.todo.presentation

import com.example.todo.application.ProjectMemberInfo
import com.example.todo.application.ProjectService
import com.example.todo.application.UserContext
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.ProjectStats
import com.example.todo.presentation.api.ProjectsApi
import com.example.todo.presentation.model.AddProjectMemberRequest
import com.example.todo.presentation.model.CreateProjectRequest
import com.example.todo.presentation.model.ProjectMemberResponse
import com.example.todo.presentation.model.ProjectResponse
import com.example.todo.presentation.model.ProjectStatsResponse
import com.example.todo.presentation.model.UpdateProjectRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.presentation.model.ProjectRole as ApiProjectRole

@RestController
class ProjectController(
    private val projectService: ProjectService,
    private val userContext: UserContext,
) : ProjectsApi {
    @Transactional(readOnly = true)
    override fun listProjects(): ResponseEntity<List<ProjectResponse>> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(projectService.listForUser(userId).map { it.toResponse() })
    }

    @Transactional
    override fun createProject(createProjectRequest: CreateProjectRequest): ResponseEntity<ProjectResponse> {
        val userId = userContext.currentUserId()
        val created = projectService.create(
            userId = userId,
            name = createProjectRequest.name,
            description = createProjectRequest.description,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @Transactional(readOnly = true)
    override fun getProject(projectId: UUID): ResponseEntity<ProjectResponse> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(projectService.getForUser(projectId, userId).toResponse())
    }

    @Transactional(readOnly = true)
    override fun getProjectStats(projectId: UUID): ResponseEntity<ProjectStatsResponse> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(projectService.getStats(projectId, userId).toResponse())
    }

    @Transactional
    override fun updateProject(
        projectId: UUID,
        updateProjectRequest: UpdateProjectRequest,
    ): ResponseEntity<ProjectResponse> {
        val userId = userContext.currentUserId()
        val updated = projectService.update(
            projectId = projectId,
            userId = userId,
            name = updateProjectRequest.name,
            description = updateProjectRequest.description,
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    @Transactional
    override fun deleteProject(projectId: UUID): ResponseEntity<Unit> {
        val userId = userContext.currentUserId()
        projectService.delete(projectId, userId)
        return ResponseEntity.noContent().build()
    }

    @Transactional(readOnly = true)
    override fun listProjectMembers(projectId: UUID): ResponseEntity<List<ProjectMemberResponse>> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(projectService.listMembers(projectId, userId).map { it.toResponse() })
    }

    @Transactional
    override fun addProjectMember(
        projectId: UUID,
        addProjectMemberRequest: AddProjectMemberRequest,
    ): ResponseEntity<ProjectMemberResponse> {
        val userId = userContext.currentUserId()
        val role = addProjectMemberRequest.role?.let { ProjectRole.valueOf(it.name) } ?: ProjectRole.MEMBER
        val added = projectService.addMember(
            projectId = projectId,
            actorUserId = userId,
            username = addProjectMemberRequest.username,
            role = role,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(added.toResponse())
    }

    @Transactional
    override fun removeProjectMember(projectId: UUID, userId: UUID): ResponseEntity<Unit> {
        val actorUserId = userContext.currentUserId()
        projectService.removeMember(projectId, actorUserId, userId)
        return ResponseEntity.noContent().build()
    }
}

private fun Project.toResponse() = ProjectResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    createdBy = createdBy,
    createdAt = createdAt.atOffset(ZoneOffset.UTC),
)

private fun ProjectMemberInfo.toResponse() = ProjectMemberResponse(
    projectId = projectId,
    userId = userId,
    username = username,
    role = ApiProjectRole.valueOf(role.name),
    joinedAt = joinedAt.atOffset(ZoneOffset.UTC),
)

private fun ProjectStats.toResponse() = ProjectStatsResponse(
    projectId = projectId,
    totalTodos = totalTodos,
    completedTodos = completedTodos,
    activeTodos = activeTodos,
    overdueTodos = overdueTodos,
    highPriorityTodos = highPriorityTodos,
    mediumPriorityTodos = mediumPriorityTodos,
    lowPriorityTodos = lowPriorityTodos,
)
