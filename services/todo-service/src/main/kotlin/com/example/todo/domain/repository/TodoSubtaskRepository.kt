package com.example.todo.domain.repository

import com.example.todo.domain.model.TodoSubtask
import java.util.UUID

interface TodoSubtaskRepository {
    fun save(subtask: TodoSubtask): TodoSubtask

    fun findAllByTodoIdOrderByPositionAsc(todoId: UUID): List<TodoSubtask>

    fun findById(id: UUID): TodoSubtask?

    fun deleteById(id: UUID)

    fun deleteAllByTodoId(todoId: UUID)
}
