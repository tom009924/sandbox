package com.example.todo.infrastructure.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.ObjectMapper

class AdminApiKeyFilterTest {
    private val objectMapper = ObjectMapper()
    private val filter = AdminApiKeyFilter("expected-secret-key", objectMapper)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `allows request with matching admin api key`() {
        val request = MockHttpServletRequest("GET", "/api/admin/stats").apply {
            addHeader(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "expected-secret-key")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        val auth = SecurityContextHolder.getContext().authentication
        assertTrue(auth?.authorities?.any { it.authority == "ROLE_ADMIN" } == true)
    }

    @Test
    fun `rejects request with mismatched admin api key`() {
        val request = MockHttpServletRequest("GET", "/api/admin/stats").apply {
            addHeader(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "wrong-secret-key")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `rejects request without admin api key`() {
        val request = MockHttpServletRequest("GET", "/api/admin/stats")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `skips public actuator endpoints and enforces admin key on protected actuator endpoints`() {
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/health"), MockHttpServletResponse(), MockFilterChain())
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/info"), MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val unauthActuator = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/metrics"), unauthActuator, MockFilterChain())
        assertEquals(401, unauthActuator.status)

        val secConfig = SecurityConfiguration(
            JwtTokenProvider("test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256", 3600),
            objectMapper,
            "expected-secret-key",
        )
        val encoder = secConfig.passwordEncoder()
        val hash = encoder.encode("pass123")
        assertTrue(encoder.matches("pass123", hash))
        assertFalse(encoder.matches("wrong", hash))

        val entryResp = MockHttpServletResponse()
        secConfig.authenticationEntryPoint().commence(
            MockHttpServletRequest("GET", "/api/todos"),
            entryResp,
            BadCredentialsException("Unauth"),
        )
        assertEquals(401, entryResp.status)

        val deniedResp = MockHttpServletResponse()
        secConfig.accessDeniedHandler().handle(
            MockHttpServletRequest("GET", "/api/admin/stats"),
            deniedResp,
            AccessDeniedException("Denied"),
        )
        assertEquals(403, deniedResp.status)
    }
}
