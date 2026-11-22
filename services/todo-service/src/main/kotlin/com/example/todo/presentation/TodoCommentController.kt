package com.example.todo.presentation

import com.example.todo.application.TodoCommentInfo
import com.example.todo.application.TodoCommentService
import com.example.todo.application.UserContext
import com.example.todo.presentation.api.TodoCommentsApi
import com.example.todo.presentation.model.CreateTodoCommentRequest
import com.example.todo.presentation.model.TodoCommentResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

@RestController
class TodoCommentController(
    private val todoCommentService: TodoCommentService,
    private val userContext: UserContext,
) : TodoCommentsApi {
    @Transactional(readOnly = true)
    override fun listTodoComments(id: UUID): ResponseEntity<List<TodoCommentResponse>> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(todoCommentService.list(id, userId).map { it.toResponse() })
    }

    @Transactional
    override fun createTodoComment(
        id: UUID,
        createTodoCommentRequest: CreateTodoCommentRequest,
    ): ResponseEntity<TodoCommentResponse> {
        val userId = userContext.currentUserId()
        val created = todoCommentService.add(id, userId, createTodoCommentRequest.content)
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @Transactional
    override fun deleteTodoComment(id: UUID, commentId: UUID): ResponseEntity<Unit> {
        val userId = userContext.currentUserId()
        todoCommentService.delete(id, commentId, userId)
        return ResponseEntity.noContent().build()
    }
}

private fun TodoCommentInfo.toResponse() = TodoCommentResponse(
    id = id,
    todoId = todoId,
    userId = userId,
    username = username,
    content = content,
    createdAt = createdAt.atOffset(ZoneOffset.UTC),
)
