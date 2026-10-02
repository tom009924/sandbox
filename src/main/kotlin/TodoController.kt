package com.example.todo

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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

data class CreateTodoRequest(
    @field:NotBlank
    val title: String,
)

data class UpdateTodoRequest(
    @field:NotBlank
    val title: String,
    val completed: Boolean,
)

data class TodoResponse(
    val id: Long,
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
    fun get(@PathVariable id: Long): TodoResponse =
        findTodo(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTodoRequest): TodoResponse =
        todoRepository.save(Todo(title = request.title.trim())).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateTodoRequest,
    ): TodoResponse {
        val todo = findTodo(id)
        todo.title = request.title.trim()
        todo.completed = request.completed
        return todoRepository.save(todo).toResponse()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val todo = findTodo(id)
        todoRepository.delete(todo)
    }

    private fun findTodo(id: Long): Todo =
        todoRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Todo $id not found")
        }

    private fun Todo.toResponse() = TodoResponse(
        id = requireNotNull(id),
        title = title,
        completed = completed,
    )
}
