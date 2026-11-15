package com.example.todo.presentation

import com.example.todo.application.TodoSubtaskService
import com.example.todo.application.UserContext
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.presentation.api.TodoSubtasksApi
import com.example.todo.presentation.model.CreateTodoSubtaskRequest
import com.example.todo.presentation.model.PatchTodoSubtaskRequest
import com.example.todo.presentation.model.TodoSubtaskResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class TodoSubtaskController(
    private val todoSubtaskService: TodoSubtaskService,
    private val userContext: UserContext,
) : TodoSubtasksApi {
    @Transactional(readOnly = true)
    override fun listTodoSubtasks(id: UUID): ResponseEntity<List<TodoSubtaskResponse>> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(todoSubtaskService.list(id, userId).map { it.toResponse() })
    }

    @Transactional
    override fun createTodoSubtask(
        id: UUID,
        createTodoSubtaskRequest: CreateTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val userId = userContext.currentUserId()
        val created = todoSubtaskService.add(id, userId, createTodoSubtaskRequest.title)
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @Transactional
    override fun patchTodoSubtask(
        id: UUID,
        subtaskId: UUID,
        patchTodoSubtaskRequest: PatchTodoSubtaskRequest,
    ): ResponseEntity<TodoSubtaskResponse> {
        val userId = userContext.currentUserId()
        val updated = todoSubtaskService.patch(
            todoId = id,
            subtaskId = subtaskId,
            userId = userId,
            title = patchTodoSubtaskRequest.title,
            completed = patchTodoSubtaskRequest.completed,
            position = patchTodoSubtaskRequest.position,
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    @Transactional
    override fun deleteTodoSubtask(id: UUID, subtaskId: UUID): ResponseEntity<Unit> {
        val userId = userContext.currentUserId()
        todoSubtaskService.delete(id, subtaskId, userId)
        return ResponseEntity.noContent().build()
    }
}

private fun TodoSubtask.toResponse() = TodoSubtaskResponse(
    id = requireNotNull(id),
    todoId = todoId,
    title = title,
    completed = completed,
    position = position,
)
