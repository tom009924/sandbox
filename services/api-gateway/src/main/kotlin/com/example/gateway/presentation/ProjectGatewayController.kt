package com.example.gateway.presentation

import com.example.gateway.grpc.TodoGrpcClient
import com.example.gateway.presentation.api.ProjectsApi
import com.example.gateway.presentation.model.AddProjectMemberRequest
import com.example.gateway.presentation.model.CreateProjectRequest
import com.example.gateway.presentation.model.ProjectMemberResponse
import com.example.gateway.presentation.model.ProjectResponse
import com.example.gateway.presentation.model.ProjectRole
import com.example.gateway.presentation.model.UpdateProjectRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.grpc.v1.AddProjectMemberRequest as ProtoAddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest as ProtoCreateProjectRequest
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole
import com.example.todo.grpc.v1.UpdateProjectRequest as ProtoUpdateProjectRequest

@RestController
class ProjectGatewayController(
    private val todoGrpcClient: TodoGrpcClient,
) : ProjectsApi {

    override fun listProjects(): ResponseEntity<List<ProjectResponse>> {
        val projects = todoGrpcClient.listProjects(
            ListProjectsRequest.newBuilder().setUserId(currentUserId().toString()).build(),
        )
        return ResponseEntity.ok(projects.map { it.toResponse() })
    }

    override fun createProject(
        createProjectRequest: CreateProjectRequest,
    ): ResponseEntity<ProjectResponse> {
        val builder = ProtoCreateProjectRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setName(createProjectRequest.name)
        createProjectRequest.description?.let { builder.setDescription(it) }
        val created = todoGrpcClient.createProject(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun getProject(projectId: UUID): ResponseEntity<ProjectResponse> {
        val project = todoGrpcClient.getProject(
            GetProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(project.toResponse())
    }

    override fun updateProject(
        projectId: UUID,
        updateProjectRequest: UpdateProjectRequest,
    ): ResponseEntity<ProjectResponse> {
        val builder = ProtoUpdateProjectRequest.newBuilder()
            .setProjectId(projectId.toString())
            .setUserId(currentUserId().toString())
            .setName(updateProjectRequest.name)
        updateProjectRequest.description?.let { builder.setDescription(it) }
        return ResponseEntity.ok(todoGrpcClient.updateProject(builder.build()).toResponse())
    }

    override fun deleteProject(projectId: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteProject(
            DeleteProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listProjectMembers(projectId: UUID): ResponseEntity<List<ProjectMemberResponse>> {
        val members = todoGrpcClient.listProjectMembers(
            ListProjectMembersRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(members.map { it.toResponse() })
    }

    override fun addProjectMember(
        projectId: UUID,
        addProjectMemberRequest: AddProjectMemberRequest,
    ): ResponseEntity<ProjectMemberResponse> {
        val role = (addProjectMemberRequest.role ?: ProjectRole.MEMBER).toProto()
        val added = todoGrpcClient.addProjectMember(
            ProtoAddProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentUserId().toString())
                .setUsername(addProjectMemberRequest.username)
                .setRole(role)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(added.toResponse())
    }

    override fun removeProjectMember(projectId: UUID, userId: UUID): ResponseEntity<Unit> {
        todoGrpcClient.removeProjectMember(
            RemoveProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentUserId().toString())
                .setTargetUserId(userId.toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }
}

private fun ProjectMessage.toResponse() = ProjectResponse(
    id = UUID.fromString(id),
    name = name,
    description = if (hasDescription()) description else null,
    createdBy = UUID.fromString(createdBy),
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

private fun ProjectMemberMessage.toResponse() = ProjectMemberResponse(
    projectId = UUID.fromString(projectId),
    userId = UUID.fromString(userId),
    username = username,
    role = role.toApi(),
    joinedAt = Instant.parse(joinedAt).atOffset(ZoneOffset.UTC),
)

private fun ProjectRole.toProto(): ProtoProjectRole = when (this) {
    ProjectRole.OWNER -> ProtoProjectRole.OWNER
    ProjectRole.MEMBER -> ProtoProjectRole.MEMBER
}

private fun ProtoProjectRole.toApi(): ProjectRole = when (this) {
    ProtoProjectRole.OWNER -> ProjectRole.OWNER
    ProtoProjectRole.MEMBER,
    ProtoProjectRole.PROJECT_ROLE_UNSPECIFIED,
    ProtoProjectRole.UNRECOGNIZED,
    -> ProjectRole.MEMBER
}
