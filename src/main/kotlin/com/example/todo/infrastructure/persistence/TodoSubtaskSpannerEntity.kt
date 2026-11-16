package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table

@Table(name = "todo_subtasks")
class TodoSubtaskSpannerEntity(
    @PrimaryKey
    @Column(name = "id")
    var id: String,

    @Column(name = "todo_id")
    var todoId: String,

    @Column(name = "title")
    var title: String,

    @Column(name = "completed")
    var completed: Boolean = false,

    @Column(name = "position")
    var position: Long = 0,
)
