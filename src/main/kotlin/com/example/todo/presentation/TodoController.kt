package com.example.todo.presentation

import com.example.todo.application.TodoService
import com.example.todo.application.TodoCommand
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateTodoRequest(
    @field:NotBlank
    val title: String,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.MEDIUM,
)

data class UpdateTodoRequest(
    @field:NotBlank
    val title: String,
    @field:NotNull
    val completed: Boolean?,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.MEDIUM,
)

data class TodoResponse(
    val id: UUID,
    val title: String,
    val completed: Boolean,
    val description: String?,
    val dueDate: LocalDate?,
    val priority: TodoPriority,
)

@RestController
@RequestMapping("/api/todos")
class TodoController(private val todoService: TodoService) {
    @GetMapping
    fun list(): List<TodoResponse> =
        todoService.list().map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): TodoResponse =
        todoService.get(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTodoRequest): TodoResponse =
        todoService.create(
            TodoCommand(
                title = request.title,
                description = request.description,
                dueDate = request.dueDate,
                priority = request.priority,
            ),
        ).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateTodoRequest,
    ): TodoResponse =
        todoService.update(
            id,
            TodoCommand(
                title = request.title,
                completed = requireNotNull(request.completed),
                description = request.description,
                dueDate = request.dueDate,
                priority = request.priority,
            ),
        ).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        todoService.delete(id)
    }

    private fun Todo.toResponse() = TodoResponse(
        id = requireNotNull(id),
        title = title,
        completed = completed,
        description = description,
        dueDate = dueDate,
        priority = priority,
    )
}
