package com.example.todo.domain.model

import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    val username: String,
)
