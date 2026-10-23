package com.example.todo.application

import com.example.todo.domain.model.AuthenticatedUser

data class TokenResult(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
)

interface TokenIssuer {
    fun issueToken(user: AuthenticatedUser): TokenResult
}

