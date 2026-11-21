package com.example.todo.infrastructure.grpc

import com.example.todo.application.ProjectMemberInfo
import com.example.todo.application.ProjectService
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectRole
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.EmptyResponse
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectMembersResponse
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ListProjectsResponse
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.UpdateProjectRequest
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole

@Component
class ProjectGrpcService(
    private val projectService: ProjectService,
) : ProjectServiceGrpc.ProjectServiceImplBase() {

    @Transactional(readOnly = true)
    override fun listProjects(
        request: ListProjectsRequest,
        responseObserver: StreamObserver<ListProjectsResponse>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            val projects = projectService.listForUser(userId).map { it.toProto() }
            ListProjectsResponse.newBuilder().addAllProjects(projects).build()
        }
    }

    @Transactional(readOnly = true)
    override fun getProject(
        request: GetProjectRequest,
        responseObserver: StreamObserver<ProjectMessage>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val userId = parseUuid(request.userId, "user_id")
            projectService.getForUser(projectId, userId).toProto()
        }
    }

    @Transactional
    override fun createProject(
        request: CreateProjectRequest,
        responseObserver: StreamObserver<ProjectMessage>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            val description = if (request.hasDescription()) request.description else null
            projectService.create(userId, request.name, description).toProto()
        }
    }

    @Transactional
    override fun updateProject(
        request: UpdateProjectRequest,
        responseObserver: StreamObserver<ProjectMessage>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val userId = parseUuid(request.userId, "user_id")
            val description = if (request.hasDescription()) request.description else null
            projectService.update(projectId, userId, request.name, description).toProto()
        }
    }

    @Transactional
    override fun deleteProject(
        request: DeleteProjectRequest,
        responseObserver: StreamObserver<EmptyResponse>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val userId = parseUuid(request.userId, "user_id")
            projectService.delete(projectId, userId)
            EmptyResponse.getDefaultInstance()
        }
    }

    @Transactional(readOnly = true)
    override fun listProjectMembers(
        request: ListProjectMembersRequest,
        responseObserver: StreamObserver<ListProjectMembersResponse>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val userId = parseUuid(request.userId, "user_id")
            val members = projectService.listMembers(projectId, userId).map { it.toProto() }
            ListProjectMembersResponse.newBuilder().addAllMembers(members).build()
        }
    }

    @Transactional
    override fun addProjectMember(
        request: AddProjectMemberRequest,
        responseObserver: StreamObserver<ProjectMemberMessage>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val actorUserId = parseUuid(request.actorUserId, "actor_user_id")
            val role = request.role.toDomain()
            projectService.addMember(projectId, actorUserId, request.username, role).toProto()
        }
    }

    @Transactional
    override fun removeProjectMember(
        request: RemoveProjectMemberRequest,
        responseObserver: StreamObserver<EmptyResponse>,
    ) {
        executeRpc(responseObserver) {
            val projectId = parseUuid(request.projectId, "project_id")
            val actorUserId = parseUuid(request.actorUserId, "actor_user_id")
            val targetUserId = parseUuid(request.targetUserId, "target_user_id")
            projectService.removeMember(projectId, actorUserId, targetUserId)
            EmptyResponse.getDefaultInstance()
        }
    }
}

private fun Project.toProto(): ProjectMessage {
    val builder = ProjectMessage.newBuilder()
        .setId(requireNotNull(id) { "Project id must not be null" }.toString())
        .setName(name)
        .setCreatedBy(createdBy.toString())
        .setCreatedAt(createdAt.toString())
    description?.let { builder.setDescription(it) }
    return builder.build()
}

private fun ProjectMemberInfo.toProto(): ProjectMemberMessage = ProjectMemberMessage.newBuilder()
    .setProjectId(projectId.toString())
    .setUserId(userId.toString())
    .setUsername(username)
    .setRole(role.toProto())
    .setJoinedAt(joinedAt.toString())
    .build()

private fun ProjectRole.toProto(): ProtoProjectRole = when (this) {
    ProjectRole.OWNER -> ProtoProjectRole.OWNER
    ProjectRole.MEMBER -> ProtoProjectRole.MEMBER
}

private fun ProtoProjectRole.toDomain(): ProjectRole = when (this) {
    ProtoProjectRole.OWNER -> ProjectRole.OWNER
    ProtoProjectRole.MEMBER,
    ProtoProjectRole.PROJECT_ROLE_UNSPECIFIED,
    ProtoProjectRole.UNRECOGNIZED,
    -> ProjectRole.MEMBER
}
