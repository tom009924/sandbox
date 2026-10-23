package com.example.todo.infrastructure.security

import com.example.todo.application.TokenIssuer
import com.example.todo.application.TokenResult
import com.example.todo.domain.model.AuthenticatedUser
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private val secret: String,
    @Value("\${jwt.expiration:86400}")
    val expirationSeconds: Long,
) : TokenIssuer {
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

    override fun issueToken(user: AuthenticatedUser): TokenResult {
        val now = Instant.now()
        val validity = now.plus(Duration.ofSeconds(expirationSeconds))
        val token = Jwts.builder()
            .subject(user.userId.toString())
            .claim("username", user.username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(validity))
            .signWith(key)
            .compact()
        return TokenResult(
            token = token,
            tokenType = "Bearer",
            expiresIn = expirationSeconds,
        )
    }

    fun generateToken(user: AuthenticatedUser): String = issueToken(user).token

    fun validateAndGetPrincipal(token: String): AuthenticatedUser? = try {
        val claims: Claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        val userIdStr = claims.subject
        val username = claims.get("username", String::class.java)
        if (userIdStr != null && username != null) {
            AuthenticatedUser(
                userId = UUID.fromString(userIdStr),
                username = username,
            )
        } else {
            null
        }
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

