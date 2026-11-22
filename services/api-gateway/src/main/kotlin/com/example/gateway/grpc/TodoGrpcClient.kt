package com.example.gateway.grpc

import com.example.grpc.common.InternalApiKeyGrpcInterceptor
import com.example.grpc.common.TracingGrpcClientInterceptor
import com.example.todo.grpc.v1.AddProjectMemberRequest
import com.example.todo.grpc.v1.AdminStatsMessage
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateProjectRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteProjectRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.grpc.v1.GetProjectRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListProjectMembersRequest
import com.example.todo.grpc.v1.ListProjectsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.ProjectServiceGrpc
import com.example.todo.grpc.v1.RemoveProjectMemberRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.grpc.v1.UpdateProjectRequest
import com.example.todo.grpc.v1.UpdateTodoRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
class TodoGrpcClient(
    private val channel: ManagedChannel,
    private val internalApiKey: String,
    private val timeoutMillis: Long,
) : AutoCloseable {

    private fun todoStub(): TodoServiceGrpc.TodoServiceBlockingStub {
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        return TodoServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(headers),
                TracingGrpcClientInterceptor(),
            )
            .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun projectStub(): ProjectServiceGrpc.ProjectServiceBlockingStub {
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        return ProjectServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(headers),
                TracingGrpcClientInterceptor(),
            )
            .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun listTodos(request: ListTodosRequest): List<TodoMessage> =
        todoStub().listTodos(request).todosList

    fun getTodo(request: GetTodoRequest): TodoMessage =
        todoStub().getTodo(request)

    fun createTodo(request: CreateTodoRequest): TodoMessage =
        todoStub().createTodo(request)

    fun updateTodo(request: UpdateTodoRequest): TodoMessage =
        todoStub().updateTodo(request)

    fun patchTodo(request: PatchTodoRequest): TodoMessage =
        todoStub().patchTodo(request)

    fun deleteTodo(request: DeleteTodoRequest) {
        todoStub().deleteTodo(request)
    }

    fun listSubtasks(request: ListSubtasksRequest): List<TodoSubtaskMessage> =
        todoStub().listSubtasks(request).subtasksList

    fun createSubtask(request: CreateSubtaskRequest): TodoSubtaskMessage =
        todoStub().createSubtask(request)

    fun patchSubtask(request: PatchSubtaskRequest): TodoSubtaskMessage =
        todoStub().patchSubtask(request)

    fun deleteSubtask(request: DeleteSubtaskRequest) {
        todoStub().deleteSubtask(request)
    }

    fun listComments(request: ListCommentsRequest): List<TodoCommentMessage> =
        todoStub().listComments(request).commentsList

    fun createComment(request: CreateCommentRequest): TodoCommentMessage =
        todoStub().createComment(request)

    fun deleteComment(request: DeleteCommentRequest) {
        todoStub().deleteComment(request)
    }

    fun getAdminStats(): AdminStatsMessage =
        todoStub().getAdminStats(GetAdminStatsRequest.getDefaultInstance())

    fun listProjects(request: ListProjectsRequest): List<ProjectMessage> =
        projectStub().listProjects(request).projectsList

    fun getProject(request: GetProjectRequest): ProjectMessage =
        projectStub().getProject(request)

    fun createProject(request: CreateProjectRequest): ProjectMessage =
        projectStub().createProject(request)

    fun updateProject(request: UpdateProjectRequest): ProjectMessage =
        projectStub().updateProject(request)

    fun deleteProject(request: DeleteProjectRequest) {
        projectStub().deleteProject(request)
    }

    fun listProjectMembers(request: ListProjectMembersRequest): List<ProjectMemberMessage> =
        projectStub().listProjectMembers(request).membersList

    fun addProjectMember(request: AddProjectMemberRequest): ProjectMemberMessage =
        projectStub().addProjectMember(request)

    fun removeProjectMember(request: RemoveProjectMemberRequest) {
        projectStub().removeProjectMember(request)
    }

    override fun close() {
        channel.shutdown()
        if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            channel.shutdownNow()
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
