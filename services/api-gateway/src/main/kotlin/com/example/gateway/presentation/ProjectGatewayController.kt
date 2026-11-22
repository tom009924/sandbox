package com.example.gateway.presentation

import com.example.gateway.grpc.TodoGrpcClient
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.UpdateProjectRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole

enum class GatewayProjectRole {
    OWNER,
    MEMBER,
}

data class CreateProjectBody(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val name: String,
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    @field:Pattern(regexp = NO_CONTROL_CHARS_PATTERN)
    val description: String? = null,
)

data class UpdateProjectBody(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val name: String,
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    @field:Pattern(regexp = NO_CONTROL_CHARS_PATTERN)
    val description: String? = null,
)

data class AddProjectMemberBody(
    @field:NotBlank
    @field:Size(min = 1, max = MAX_PROJECT_MEMBER_USERNAME_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val username: String,
    val role: GatewayProjectRole? = null,
)

data class ProjectResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime,
)

data class ProjectMemberResponse(
    val projectId: UUID,
    val userId: UUID,
    val username: String,
    val role: GatewayProjectRole,
    val joinedAt: OffsetDateTime,
)

@RestController
class ProjectGatewayController(
    private val todoGrpcClient: TodoGrpcClient,
) {
    @GetMapping("/api/projects")
    fun listProjects(): ResponseEntity<List<ProjectResponse>> {
        val projects = todoGrpcClient.listProjects(
            ListProjectsRequest.newBuilder().setUserId(currentUserId().toString()).build(),
        )
        return ResponseEntity.ok(projects.map { it.toResponse() })
    }

    @PostMapping("/api/projects")
    fun createProject(
        @Valid @RequestBody body: CreateProjectBody,
    ): ResponseEntity<ProjectResponse> {
        val builder = CreateProjectRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setName(body.name)
        body.description?.let { builder.setDescription(it) }
        val created = todoGrpcClient.createProject(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @GetMapping("/api/projects/{projectId}")
    fun getProject(@PathVariable projectId: UUID): ResponseEntity<ProjectResponse> {
        val project = todoGrpcClient.getProject(
            GetProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(project.toResponse())
    }

    @PutMapping("/api/projects/{projectId}")
    fun updateProject(
        @PathVariable projectId: UUID,
        @Valid @RequestBody body: UpdateProjectBody,
    ): ResponseEntity<ProjectResponse> {
        val builder = UpdateProjectRequest.newBuilder()
            .setProjectId(projectId.toString())
            .setUserId(currentUserId().toString())
            .setName(body.name)
        body.description?.let { builder.setDescription(it) }
        return ResponseEntity.ok(todoGrpcClient.updateProject(builder.build()).toResponse())
    }

    @DeleteMapping("/api/projects/{projectId}")
    fun deleteProject(@PathVariable projectId: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteProject(
            DeleteProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/projects/{projectId}/members")
    fun listProjectMembers(@PathVariable projectId: UUID): ResponseEntity<List<ProjectMemberResponse>> {
        val members = todoGrpcClient.listProjectMembers(
            ListProjectMembersRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(members.map { it.toResponse() })
    }

    @PostMapping("/api/projects/{projectId}/members")
    fun addProjectMember(
        @PathVariable projectId: UUID,
        @Valid @RequestBody body: AddProjectMemberBody,
    ): ResponseEntity<ProjectMemberResponse> {
        val role = (body.role ?: GatewayProjectRole.MEMBER).toProto()
        val added = todoGrpcClient.addProjectMember(
            AddProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentUserId().toString())
                .setUsername(body.username)
                .setRole(role)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(added.toResponse())
    }

    @DeleteMapping("/api/projects/{projectId}/members/{userId}")
    fun removeProjectMember(
        @PathVariable projectId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Unit> {
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

private fun GatewayProjectRole.toProto(): ProtoProjectRole = when (this) {
    GatewayProjectRole.OWNER -> ProtoProjectRole.OWNER
    GatewayProjectRole.MEMBER -> ProtoProjectRole.MEMBER
}

private fun ProtoProjectRole.toApi(): GatewayProjectRole = when (this) {
    ProtoProjectRole.OWNER -> GatewayProjectRole.OWNER
    ProtoProjectRole.MEMBER,
    ProtoProjectRole.PROJECT_ROLE_UNSPECIFIED,
    ProtoProjectRole.UNRECOGNIZED,
    -> GatewayProjectRole.MEMBER
}
