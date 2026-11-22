package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table

@Table(name = "users")
class UserSpannerEntity(
    @PrimaryKey
    @Column(name = "id")
    var id: String,

    @Column(name = "username")
    var username: String,

    @Column(name = "password_hash")
    var passwordHash: String,
)
