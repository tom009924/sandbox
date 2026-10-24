package com.example.todo.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataTodoRepository : JpaRepository<TodoJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<TodoJpaEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): TodoJpaEntity?

    fun countByUserIdAndCompletedFalse(userId: UUID): Long
}
