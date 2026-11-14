package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import com.google.cloud.spring.data.spanner.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
@Suppress("TooManyFunctions")
interface SpringDataTodoRepository : SpannerRepository<TodoSpannerEntity, String> {
    fun findAllByUserId(userId: String): List<TodoSpannerEntity>

    fun findAllByUserIdAndCompleted(userId: String, completed: Boolean): List<TodoSpannerEntity>

    fun findAllByUserIdAndPriority(userId: String, priority: String): List<TodoSpannerEntity>

    fun findAllByUserIdAndCompletedAndPriority(
        userId: String,
        completed: Boolean,
        priority: String,
    ): List<TodoSpannerEntity>

    @Query("SELECT * FROM todos WHERE user_id = @userId AND @tag IN UNNEST(tags)")
    fun findAllByUserIdAndTag(
        @Param("userId") userId: String,
        @Param("tag") tag: String,
    ): List<TodoSpannerEntity>

    @Query("SELECT * FROM todos WHERE user_id = @userId AND completed = @completed AND @tag IN UNNEST(tags)")
    fun findAllByUserIdAndCompletedAndTag(
        @Param("userId") userId: String,
        @Param("completed") completed: Boolean,
        @Param("tag") tag: String,
    ): List<TodoSpannerEntity>

    @Query("SELECT * FROM todos WHERE user_id = @userId AND priority = @priority AND @tag IN UNNEST(tags)")
    fun findAllByUserIdAndPriorityAndTag(
        @Param("userId") userId: String,
        @Param("priority") priority: String,
        @Param("tag") tag: String,
    ): List<TodoSpannerEntity>

    @Query(
        "SELECT * FROM todos WHERE user_id = @userId AND completed = @completed " +
            "AND priority = @priority AND @tag IN UNNEST(tags)",
    )
    fun findAllByUserIdAndCompletedAndPriorityAndTag(
        @Param("userId") userId: String,
        @Param("completed") completed: Boolean,
        @Param("priority") priority: String,
        @Param("tag") tag: String,
    ): List<TodoSpannerEntity>

    fun findByIdAndUserId(id: String, userId: String): TodoSpannerEntity?

    @Query("SELECT COUNT(1) FROM todos WHERE user_id = @userId AND completed = FALSE")
    fun countByUserIdAndCompletedFalse(@Param("userId") userId: String): Long

    fun countByCompleted(completed: Boolean): Long
}
