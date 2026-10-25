package com.example.todo.presentation

import com.example.todo.application.PatchTodoCommand
import com.example.todo.application.TodoCommand
import com.example.todo.application.TodoService
import com.example.todo.application.UserContext
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.presentation.api.TodosApi
import com.example.todo.presentation.model.CreateTodoRequest
import com.example.todo.presentation.model.PatchTodoRequest
import com.example.todo.presentation.model.UpdateTodoRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.todo.presentation.model.Todo as TodoResponse
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority

@RestController
class TodoController(
    private val todoService: TodoService,
    private val userContext: UserContext,
) : TodosApi {
    override fun listTodos(
        completed: Boolean?,
        priority: ApiTodoPriority?,
    ): ResponseEntity<List<TodoResponse>> {
        val userId = userContext.currentUserId()
        val todos = todoService.list(userId, completed, priority?.toDomain())
        return ResponseEntity.ok(todos.map { it.toResponse() })
    }

    override fun getTodo(id: UUID): ResponseEntity<TodoResponse> {
        val userId = userContext.currentUserId()
        return ResponseEntity.ok(todoService.get(id, userId).toResponse())
    }

    @Transactional
    override fun createTodo(
        idempotencyKey: String,
        createTodoRequest: CreateTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val userId = userContext.currentUserId()
        val created = todoService.createIdempotent(
            userId,
            idempotencyKey,
            TodoCommand(
                title = createTodoRequest.title,
                description = createTodoRequest.description,
                dueDate = createTodoRequest.dueDate,
                priority = createTodoRequest.priority?.toDomain(),
                estimatedMinutes = createTodoRequest.estimatedMinutes,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun updateTodo(
        id: UUID,
        updateTodoRequest: UpdateTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val userId = userContext.currentUserId()
        val updated = todoService.update(
            id,
            userId,
            TodoCommand(
                title = updateTodoRequest.title,
                completed = updateTodoRequest.completed,
                description = updateTodoRequest.description,
                dueDate = updateTodoRequest.dueDate,
                priority = updateTodoRequest.priority?.toDomain() ?: TodoPriority.MEDIUM,
                estimatedMinutes = updateTodoRequest.estimatedMinutes,
            ),
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    override fun patchTodo(
        id: UUID,
        patchTodoRequest: PatchTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val userId = userContext.currentUserId()
        val updated = todoService.patch(
            id,
            userId,
            PatchTodoCommand(
                title = patchTodoRequest.title,
                completed = patchTodoRequest.completed,
                description = patchTodoRequest.description,
                dueDate = patchTodoRequest.dueDate,
                priority = patchTodoRequest.priority?.toDomain(),
                estimatedMinutes = patchTodoRequest.estimatedMinutes,
            ),
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    override fun deleteTodo(id: UUID): ResponseEntity<Unit> {
        val userId = userContext.currentUserId()
        todoService.delete(id, userId)
        return ResponseEntity.noContent().build()
    }

    private fun Todo.toResponse() = TodoResponse(
        id = requireNotNull(id),
        userId = userId,
        title = title,
        completed = completed,
        description = description,
        dueDate = dueDate,
        priority = ApiTodoPriority.valueOf(priority.name),
        estimatedMinutes = estimatedMinutes,
    )

    private fun ApiTodoPriority.toDomain(): TodoPriority =
        TodoPriority.valueOf(name)
}
