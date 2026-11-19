package com.example.identity.application

import com.example.identity.domain.model.AuthenticatedUser

data class TokenResult(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
)

interface TokenIssuer {
    fun issueToken(user: AuthenticatedUser): TokenResult
}
