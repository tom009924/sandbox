package com.example.todo.gateway

import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.presentation.api.ProjectsApi
import com.example.todo.presentation.model.AddProjectMemberRequest
import com.example.todo.presentation.model.CreateProjectRequest
import com.example.todo.presentation.model.ProjectMemberResponse
import com.example.todo.presentation.model.ProjectResponse
import com.example.todo.presentation.model.UpdateProjectRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.todo.grpc.v1.AddProjectMemberRequest as ProtoAddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest as ProtoCreateProjectRequest
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole
import com.example.todo.grpc.v1.UpdateProjectRequest as ProtoUpdateProjectRequest
import com.example.todo.presentation.model.ProjectRole as ApiProjectRole

@RestController
class TestProjectGatewayController(
    private val bridge: InProcessTestGrpcBridge,
) : ProjectsApi {

    override fun listProjects(): ResponseEntity<List<ProjectResponse>> {
        val projects = bridge.projectStub.listProjects(
            ListProjectsRequest.newBuilder().setUserId(currentAuthenticatedUserId().toString()).build(),
        ).projectsList
        return ResponseEntity.ok(projects.map { it.toResponse() })
    }

    override fun createProject(createProjectRequest: CreateProjectRequest): ResponseEntity<ProjectResponse> {
        val builder = ProtoCreateProjectRequest.newBuilder()
            .setUserId(currentAuthenticatedUserId().toString())
            .setName(createProjectRequest.name)
        createProjectRequest.description?.let { builder.setDescription(it) }
        val created = bridge.projectStub.createProject(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun getProject(projectId: UUID): ResponseEntity<ProjectResponse> {
        val project = bridge.projectStub.getProject(
            GetProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentAuthenticatedUserId().toString())
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
            .setUserId(currentAuthenticatedUserId().toString())
            .setName(updateProjectRequest.name)
        updateProjectRequest.description?.let { builder.setDescription(it) }
        return ResponseEntity.ok(bridge.projectStub.updateProject(builder.build()).toResponse())
    }

    override fun deleteProject(projectId: UUID): ResponseEntity<Unit> {
        bridge.projectStub.deleteProject(
            DeleteProjectRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listProjectMembers(projectId: UUID): ResponseEntity<List<ProjectMemberResponse>> {
        val members = bridge.projectStub.listProjectMembers(
            ListProjectMembersRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        ).membersList
        return ResponseEntity.ok(members.map { it.toResponse() })
    }

    override fun addProjectMember(
        projectId: UUID,
        addProjectMemberRequest: AddProjectMemberRequest,
    ): ResponseEntity<ProjectMemberResponse> {
        val role = ProtoProjectRole.valueOf((addProjectMemberRequest.role ?: ApiProjectRole.MEMBER).name)
        val added = bridge.projectStub.addProjectMember(
            ProtoAddProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentAuthenticatedUserId().toString())
                .setUsername(addProjectMemberRequest.username)
                .setRole(role)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(added.toResponse())
    }

    override fun removeProjectMember(projectId: UUID, userId: UUID): ResponseEntity<Unit> {
        bridge.projectStub.removeProjectMember(
            RemoveProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(currentAuthenticatedUserId().toString())
                .setTargetUserId(userId.toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }
}
