package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table

@Table(name = "user_settings")
class UserSettingsSpannerEntity(
    @PrimaryKey
    @Column(name = "user_id")
    var userId: String,

    @Column(name = "default_priority")
    var defaultPriority: String,

    @Column(name = "default_estimated_minutes")
    var defaultEstimatedMinutes: Int? = null,

    @Column(name = "max_active_todos")
    var maxActiveTodos: Int? = null,
)
