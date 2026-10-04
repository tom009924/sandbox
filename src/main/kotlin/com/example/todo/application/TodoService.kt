package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import java.time.LocalDate
import java.util.UUID

data class TodoCommand(
    val title: String,
    val completed: Boolean = false,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.MEDIUM,
    val estimatedMinutes: Int? = null,
)

class TodoService(private val todoRepository: TodoRepository) {
    fun list(): List<Todo> = todoRepository.findAll()

    fun get(id: UUID): Todo = findTodo(id)

    fun create(command: TodoCommand): Todo =
        todoRepository.save(
            Todo(
                title = command.title.trim(),
                description = command.description,
                dueDate = command.dueDate,
                priority = command.priority,
                estimatedMinutes = command.estimatedMinutes,
            ),
        )

    fun update(id: UUID, command: TodoCommand): Todo {
        val todo = findTodo(id)
        return todoRepository.save(
            todo.copy(
                title = command.title.trim(),
                completed = command.completed,
                description = command.description,
                dueDate = command.dueDate,
                priority = command.priority,
                estimatedMinutes = command.estimatedMinutes,
            ),
        )
    }

    fun delete(id: UUID) {
        todoRepository.delete(findTodo(id))
    }

    private fun findTodo(id: UUID): Todo =
        todoRepository.findById(id) ?: throw TodoNotFoundException(id)
}
