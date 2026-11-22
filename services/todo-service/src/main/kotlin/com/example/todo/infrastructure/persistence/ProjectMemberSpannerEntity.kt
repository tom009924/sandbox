package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table
import java.time.Instant

@Table(name = "project_members")
class ProjectMemberSpannerEntity(
    @PrimaryKey(keyOrder = 1)
    @Column(name = "project_id")
    var projectId: String,

    @PrimaryKey(keyOrder = 2)
    @Column(name = "user_id")
    var userId: String,

    @Column(name = "role")
    var role: String,

    @Column(name = "joined_at")
    var joinedAt: Instant,
)
