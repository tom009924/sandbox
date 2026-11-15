package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table
import java.time.Instant

@Table(name = "todo_comments")
class TodoCommentSpannerEntity(
    @PrimaryKey
    @Column(name = "id")
    var id: String,

    @Column(name = "todo_id")
    var todoId: String,

    @Column(name = "user_id")
    var userId: String,

    @Column(name = "content")
    var content: String,

    @Column(name = "created_at")
    var createdAt: Instant,
)
