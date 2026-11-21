package com.example.identity.infrastructure.security

import com.example.identity.domain.model.AuthenticatedUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class JwtTokenProviderTest {
    private val provider = JwtTokenProvider(
        secret = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256",
        expirationSeconds = 3600L,
    )

    @Test
    fun `issueToken and validateAndGetPrincipal round-trip user`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "alice")

        val tokenResult = provider.issueToken(user)
        val parsed = provider.validateAndGetPrincipal(tokenResult.token)

        assertEquals(user, parsed)
    }

    @Test
    fun `validateAndGetPrincipal returns null for invalid token`() {
        assertNull(provider.validateAndGetPrincipal("not-a-valid-jwt"))
    }
}
