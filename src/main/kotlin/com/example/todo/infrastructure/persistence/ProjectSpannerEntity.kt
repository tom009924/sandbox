package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table
import java.time.Instant

@Table(name = "projects")
class ProjectSpannerEntity(
    @PrimaryKey
    @Column(name = "id")
    var id: String,

    @Column(name = "name")
    var name: String,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "created_by")
    var createdBy: String,

    @Column(name = "created_at")
    var createdAt: Instant,
)
