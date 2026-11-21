package com.example.gateway.security

import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    val username: String,
)
