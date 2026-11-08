package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import com.google.cloud.spring.data.spanner.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SpringDataTodoRepository : SpannerRepository<TodoSpannerEntity, String> {
    fun findAllByUserId(userId: String): List<TodoSpannerEntity>

    fun findAllByUserIdAndCompleted(userId: String, completed: Boolean): List<TodoSpannerEntity>

    fun findAllByUserIdAndPriority(userId: String, priority: String): List<TodoSpannerEntity>

    fun findAllByUserIdAndCompletedAndPriority(
        userId: String,
        completed: Boolean,
        priority: String,
    ): List<TodoSpannerEntity>

    fun findByIdAndUserId(id: String, userId: String): TodoSpannerEntity?

    @Query("SELECT COUNT(1) FROM todos WHERE user_id = @userId AND completed = FALSE")
    fun countByUserIdAndCompletedFalse(@Param("userId") userId: String): Long

    fun countByCompleted(completed: Boolean): Long
}
