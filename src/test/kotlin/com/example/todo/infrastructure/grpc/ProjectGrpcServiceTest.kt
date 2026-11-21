package com.example.todo.infrastructure.grpc

import com.example.todo.application.ProjectMemberInfo
import com.example.todo.application.ProjectService
import com.example.todo.domain.exception.ProjectForbiddenException
import com.example.todo.domain.exception.ProjectMemberAlreadyExistsException
import com.example.todo.domain.exception.ProjectNotFoundException
import com.example.todo.domain.model.Project
import com.example.todo.domain.model.ProjectRole
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.UpdateProjectRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import com.example.todo.grpc.v1.ProjectRole as ProtoProjectRole

class ProjectGrpcServiceTest {
    private val projectService: ProjectService = mock(ProjectService::class.java)

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val memberId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val projectId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @BeforeEach
    fun setUp() {
        val serverName = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    ProjectGrpcService(projectService),
                    InternalApiKeyGrpcInterceptor(TEST_API_KEY),
                ),
            )
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `handles project CRUD RPCs`() {
        val now = Instant.parse("2026-05-01T12:00:00Z")
        val project = Project(
            id = projectId,
            name = "Platform",
            description = "Core services",
            createdBy = userId,
            createdAt = now,
        )
        `when`(projectService.create(userId, "Platform", "Core services")).thenReturn(project)
        `when`(projectService.listForUser(userId)).thenReturn(listOf(project))
        `when`(projectService.getForUser(projectId, userId)).thenReturn(project)
        `when`(projectService.update(projectId, userId, "Platform v2", null))
            .thenReturn(project.copy(name = "Platform v2", description = null))

        val stub = authenticatedStub()
        val created = stub.createProject(
            CreateProjectRequest.newBuilder()
                .setUserId(userId.toString())
                .setName("Platform")
                .setDescription("Core services")
                .build(),
        )
        assertEquals("Platform", created.name)
        assertEquals(
            1,
            stub.listProjects(ListProjectsRequest.newBuilder().setUserId(userId.toString()).build()).projectsCount,
        )
        assertEquals(
            projectId.toString(),
            stub.getProject(
                GetProjectRequest.newBuilder().setProjectId(projectId.toString()).setUserId(userId.toString()).build(),
            ).id,
        )
        assertEquals(
            "Platform v2",
            stub.updateProject(
                UpdateProjectRequest.newBuilder()
                    .setProjectId(projectId.toString())
                    .setUserId(userId.toString())
                    .setName("Platform v2")
                    .build(),
            ).name,
        )
        stub.deleteProject(
            DeleteProjectRequest.newBuilder().setProjectId(projectId.toString()).setUserId(userId.toString()).build(),
        )
        verify(projectService).delete(projectId, userId)
    }

    @Test
    fun `handles project member RPCs`() {
        val now = Instant.parse("2026-05-01T12:00:00Z")
        val member = ProjectMemberInfo(
            projectId = projectId,
            userId = memberId,
            username = "bob",
            role = ProjectRole.MEMBER,
            joinedAt = now,
        )
        `when`(projectService.addMember(projectId, userId, "bob", ProjectRole.MEMBER)).thenReturn(member)
        `when`(projectService.listMembers(projectId, userId)).thenReturn(listOf(member))

        val stub = authenticatedStub()
        val added = stub.addProjectMember(
            AddProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(userId.toString())
                .setUsername("bob")
                .setRole(ProtoProjectRole.MEMBER)
                .build(),
        )
        assertEquals("bob", added.username)
        assertEquals(
            1,
            stub.listProjectMembers(
                ListProjectMembersRequest.newBuilder()
                    .setProjectId(projectId.toString())
                    .setUserId(userId.toString())
                    .build(),
            ).membersCount,
        )
        stub.removeProjectMember(
            RemoveProjectMemberRequest.newBuilder()
                .setProjectId(projectId.toString())
                .setActorUserId(userId.toString())
                .setTargetUserId(memberId.toString())
                .build(),
        )
        verify(projectService).removeMember(projectId, userId, memberId)
    }

    @Test
    fun `maps project exceptions to gRPC status codes`() {
        `when`(projectService.getForUser(projectId, userId)).thenThrow(ProjectNotFoundException(projectId))
        `when`(projectService.delete(projectId, userId)).thenThrow(ProjectForbiddenException("Forbidden"))
        `when`(projectService.addMember(projectId, userId, "bob", ProjectRole.MEMBER))
            .thenThrow(ProjectMemberAlreadyExistsException(projectId, memberId))

        val stub = authenticatedStub()
        assertEquals(
            Status.Code.NOT_FOUND,
            assertThrows(StatusRuntimeException::class.java) {
                stub.getProject(
                    GetProjectRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(userId.toString())
                        .build(),
                )
            }.status.code,
        )
        assertEquals(
            Status.Code.PERMISSION_DENIED,
            assertThrows(StatusRuntimeException::class.java) {
                stub.deleteProject(
                    DeleteProjectRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setUserId(userId.toString())
                        .build(),
                )
            }.status.code,
        )
        assertEquals(
            Status.Code.ALREADY_EXISTS,
            assertThrows(StatusRuntimeException::class.java) {
                stub.addProjectMember(
                    AddProjectMemberRequest.newBuilder()
                        .setProjectId(projectId.toString())
                        .setActorUserId(userId.toString())
                        .setUsername("bob")
                        .setRole(ProtoProjectRole.MEMBER)
                        .build(),
                )
            }.status.code,
        )
    }

    private fun authenticatedStub(): ProjectServiceGrpc.ProjectServiceBlockingStub {
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, TEST_API_KEY)
        }
        return ProjectServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private companion object {
        const val TEST_API_KEY = "test-internal-key"
    }
}
