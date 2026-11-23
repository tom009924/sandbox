package com.example.todo.gateway

import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.presentation.api.TodoCommentsApi
import com.example.todo.presentation.api.TodoSubtasksApi
import com.example.todo.presentation.model.CreateTodoCommentRequest
import com.example.todo.presentation.model.CreateTodoSubtaskRequest
import com.example.todo.presentation.model.PatchTodoSubtaskRequest
import com.example.todo.presentation.model.TodoCommentResponse
import com.example.todo.presentation.model.TodoSubtaskResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class TestTodoCollaborationGatewayController(
    private val bridge: InProcessTestGrpcBridge,
) : TodoSubtasksApi, TodoCommentsApi {

    override fun listTodoSubtasks(id: UUID): ResponseEntity<List<TodoSubtaskResponse>> {
        val subtasks = bridge.todoStub.listSubtasks(
            ListSubtasksRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        ).subtasksList
        return ResponseEntity.ok(subtasks.map { it.toResponse() })
    }

    override fun createTodoSubtask(
        id: UUID,
        createTodoSubtaskRequest: CreateTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val created = bridge.todoStub.createSubtask(
            CreateSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .setTitle(createTodoSubtaskRequest.title)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun patchTodoSubtask(
        id: UUID,
        subtaskId: UUID,
        patchTodoSubtaskRequest: PatchTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val builder = PatchSubtaskRequest.newBuilder()
            .setTodoId(id.toString())
            .setSubtaskId(subtaskId.toString())
            .setUserId(currentAuthenticatedUserId().toString())
        patchTodoSubtaskRequest.title?.let { builder.setTitle(it) }
        patchTodoSubtaskRequest.completed?.let { builder.setCompleted(it) }
        patchTodoSubtaskRequest.position?.let { builder.setPosition(it) }
        return ResponseEntity.ok(bridge.todoStub.patchSubtask(builder.build()).toResponse())
    }

    override fun deleteTodoSubtask(id: UUID, subtaskId: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteSubtask(
            DeleteSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setSubtaskId(subtaskId.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun listTodoComments(id: UUID): ResponseEntity<List<TodoCommentResponse>> {
        val comments = bridge.todoStub.listComments(
            ListCommentsRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        ).commentsList
        return ResponseEntity.ok(comments.map { it.toResponse() })
    }

    override fun createTodoComment(
        id: UUID,
        createTodoCommentRequest: CreateTodoCommentRequest,
    ): ResponseEntity<TodoCommentResponse> {
        val created = bridge.todoStub.createComment(
            CreateCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .setContent(createTodoCommentRequest.content)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun deleteTodoComment(id: UUID, commentId: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteComment(
            DeleteCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setCommentId(commentId.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }
}
