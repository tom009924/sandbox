package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoPriority
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataTodoRepository : JpaRepository<TodoJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<TodoJpaEntity>

    fun findAllByUserIdAndCompleted(userId: UUID, completed: Boolean): List<TodoJpaEntity>

    fun findAllByUserIdAndPriority(userId: UUID, priority: TodoPriority): List<TodoJpaEntity>

    fun findAllByUserIdAndCompletedAndPriority(
        userId: UUID,
        completed: Boolean,
        priority: TodoPriority,
    ): List<TodoJpaEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): TodoJpaEntity?

    fun countByUserIdAndCompletedFalse(userId: UUID): Long

    fun countByCompleted(completed: Boolean): Long
}
