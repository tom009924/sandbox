package com.example.todo.infrastructure.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.ObjectMapper

class AdminApiKeyFilterTest {
    private val filter = AdminApiKeyFilter("expected-secret-key", ObjectMapper())

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
}
