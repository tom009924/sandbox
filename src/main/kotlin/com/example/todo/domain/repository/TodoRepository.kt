package com.example.todo.domain.repository

import com.example.todo.domain.model.Todo
import java.util.UUID

interface TodoRepository {
    fun findAll(): List<Todo>

    fun findAllByUserId(userId: UUID): List<Todo>

    fun findById(id: UUID): Todo?

    fun findByIdAndUserId(id: UUID, userId: UUID): Todo?

    fun countByUserIdAndCompletedFalse(userId: UUID): Long

    fun count(): Long

    fun countByCompleted(completed: Boolean): Long

    fun save(todo: Todo): Todo

    fun delete(todo: Todo)
}
