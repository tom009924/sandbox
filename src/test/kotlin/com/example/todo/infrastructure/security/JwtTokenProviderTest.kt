package com.example.todo.infrastructure.security

import com.example.todo.domain.model.AuthenticatedUser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.UUID

class JwtTokenProviderTest {
    private val secret = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-testing"
    private val provider = JwtTokenProvider(secret = secret, expirationSeconds = 3600)

    @AfterEach
    fun cleanSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `issueToken and validateAndGetPrincipal successfully`() {
        val user = AuthenticatedUser(userId = UUID.randomUUID(), username = "alice")
        val token = provider.generateToken(user)

        val principal = provider.validateAndGetPrincipal(token)
        assertNotNull(principal)
        assertEquals(user.userId, principal?.userId)
        assertEquals(user.username, principal?.username)
    }

    @Test
    fun `validate returns null for malformed token`() {
        assertNull(provider.validateAndGetPrincipal("invalid-token"))
    }

    @Test
    fun `validate returns null for token signed with different secret`() {
        val differentSecret = "different-secret-key-must-be-at-least-256-bits-long-for-testing"
        val otherKey = Keys.hmacShaKeyFor(differentSecret.toByteArray(StandardCharsets.UTF_8))
        val otherToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("username", "bob")
            .signWith(otherKey)
            .compact()

        assertNull(provider.validateAndGetPrincipal(otherToken))
    }

    @Test
    fun `validate returns null when subject or username is missing`() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val tokenMissingUsername = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .signWith(key)
            .compact()
        assertNull(provider.validateAndGetPrincipal(tokenMissingUsername))

        val tokenMissingSubject = Jwts.builder()
            .claim("username", "charlie")
            .signWith(key)
            .compact()
        assertNull(provider.validateAndGetPrincipal(tokenMissingSubject))
    }

    @Test
    fun `validate returns null when subject is not a valid UUID`() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val tokenWithInvalidUuidSubject = Jwts.builder()
            .subject("not-a-valid-uuid")
            .claim("username", "frank")
            .signWith(key)
            .compact()

        assertNull(provider.validateAndGetPrincipal(tokenWithInvalidUuidSubject))
    }

    @Test
    fun `validate returns null when token is expired`() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val expiredToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("username", "david")
            .expiration(Date(System.currentTimeMillis() - 10000))
            .signWith(key)
            .compact()

        assertNull(provider.validateAndGetPrincipal(expiredToken))
    }

    @Test
    fun `filter sets authentication when valid bearer token is provided`() {
        val filter = JwtAuthenticationFilter(provider)
        val user = AuthenticatedUser(userId = UUID.randomUUID(), username = "eve")
        val token = provider.generateToken(user)

        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer $token")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val auth = checkNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals(user, auth.principal)
    }

    @Test
    fun `filter ignores request when authorization header is missing or non bearer`() {
        val filter = JwtAuthenticationFilter(provider)

        val requestNoHeader = MockHttpServletRequest()
        filter.doFilter(requestNoHeader, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val requestBasic = MockHttpServletRequest()
        requestBasic.addHeader("Authorization", "Basic dXNlcjpwYXNz")
        filter.doFilter(requestBasic, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val requestEmptyBearer = MockHttpServletRequest()
        requestEmptyBearer.addHeader("Authorization", "Bearer   ")
        filter.doFilter(requestEmptyBearer, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `filter ignores request when bearer token is invalid`() {
        val filter = JwtAuthenticationFilter(provider)
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer invalid.jwt.token")

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}

