package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.TodoRepository
import java.util.UUID

class TodoService(private val todoRepository: TodoRepository) {
    fun list(): List<Todo> = todoRepository.findAll()

    fun get(id: UUID): Todo = findTodo(id)

    fun create(title: String): Todo =
        todoRepository.save(Todo(title = title.trim()))

    fun update(id: UUID, title: String, completed: Boolean): Todo {
        val todo = findTodo(id)
        return todoRepository.save(todo.copy(title = title.trim(), completed = completed))
    }

    fun delete(id: UUID) {
        todoRepository.delete(findTodo(id))
    }

    private fun findTodo(id: UUID): Todo =
        todoRepository.findById(id) ?: throw TodoNotFoundException(id)
}
