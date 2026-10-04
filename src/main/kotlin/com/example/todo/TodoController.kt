package com.example.todo

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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class CreateTodoRequest(
    @field:NotBlank
    val title: String,
)

data class UpdateTodoRequest(
    @field:NotBlank
    val title: String,
    @field:NotNull
    val completed: Boolean?,
)

data class TodoResponse(
    val id: UUID,
    val title: String,
    val completed: Boolean,
)

@RestController
@RequestMapping("/api/todos")
class TodoController(private val todoRepository: TodoRepository) {
    @GetMapping
    fun list(): List<TodoResponse> =
        todoRepository.findAll().map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): TodoResponse =
        findTodo(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTodoRequest): TodoResponse =
        todoRepository.save(Todo(title = request.title.trim())).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateTodoRequest,
    ): TodoResponse {
        val todo = findTodo(id)
        todo.title = request.title.trim()
        todo.completed = requireNotNull(request.completed) { "completed is required" }
        return todoRepository.save(todo).toResponse()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        val todo = findTodo(id)
        todoRepository.delete(todo)
    }

    private fun findTodo(id: UUID): Todo =
        todoRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Todo $id not found")
        }

    private fun Todo.toResponse() = TodoResponse(
        id = requireNotNull(id),
        title = title,
        completed = completed,
    )
}
