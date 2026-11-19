package com.example.identity.domain.model

import java.util.UUID

data class User(
    val id: UUID? = null,
    val username: String,
    val passwordHash: String,
)
