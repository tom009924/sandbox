package com.example.todo.domain.repository

import com.example.todo.domain.model.TodoComment
import java.util.UUID

interface TodoCommentRepository {
    fun save(comment: TodoComment): TodoComment

    fun findAllByTodoIdOrderByCreatedAtAsc(todoId: UUID): List<TodoComment>

    fun findById(id: UUID): TodoComment?

    fun deleteById(id: UUID)

    fun deleteAllByTodoId(todoId: UUID)
}
