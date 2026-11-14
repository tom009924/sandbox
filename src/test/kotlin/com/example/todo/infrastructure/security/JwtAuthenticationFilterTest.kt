package com.example.todo.infrastructure.security

import com.example.todo.domain.model.AuthenticatedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class JwtAuthenticationFilterTest {
    private val jwtTokenProvider = JwtTokenProvider(
        secret = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256",
        expirationSeconds = 3600,
    )
    private val filter = JwtAuthenticationFilter(jwtTokenProvider)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `sets user authentication when bearer token is valid and context is empty`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "alice")
        val token = jwtTokenProvider.generateToken(user)
        val request = MockHttpServletRequest("GET", "/api/todos").apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val auth = SecurityContextHolder.getContext().authentication
        assertEquals(user, auth?.principal)
        assertTrue(auth?.authorities?.any { it.authority == "ROLE_USER" } == true)
    }

    @Test
    fun `does not overwrite existing authentication such as admin role`() {
        val existingAuth = UsernamePasswordAuthenticationToken(
            "admin",
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        SecurityContextHolder.getContext().authentication = existingAuth

        val user = AuthenticatedUser(UUID.randomUUID(), "alice")
        val token = jwtTokenProvider.generateToken(user)
        val request = MockHttpServletRequest("GET", "/api/admin/stats").apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val auth = SecurityContextHolder.getContext().authentication
        assertEquals("admin", auth?.principal)
        assertTrue(auth?.authorities?.any { it.authority == "ROLE_ADMIN" } == true)
    }
}
