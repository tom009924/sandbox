package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table
import java.time.LocalDate

@Table(name = "todos")
@Suppress("LongParameterList")
class TodoSpannerEntity(
    @PrimaryKey
    @Column(name = "id")
    var id: String,

    @Column(name = "user_id")
    var userId: String,

    @Column(name = "title")
    var title: String,

    @Column(name = "completed")
    var completed: Boolean = false,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "due_date")
    var dueDate: LocalDate? = null,

    @Column(name = "priority")
    var priority: String,

    @Column(name = "estimated_minutes")
    var estimatedMinutes: Int? = null,
)
