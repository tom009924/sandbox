package com.example.gateway.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @param:Value("\${jwt.secret:default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private val secret: String,
) {
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

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
