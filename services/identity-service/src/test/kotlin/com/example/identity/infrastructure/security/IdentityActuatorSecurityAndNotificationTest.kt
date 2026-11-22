package com.example.identity.infrastructure.security

import com.example.identity.domain.notification.EmailNotification
import com.example.identity.domain.notification.EmailNotificationException
import com.example.identity.infrastructure.notification.EmailNotificationProperties
import com.example.identity.infrastructure.notification.HttpEmailNotificationSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class IdentityActuatorSecurityAndNotificationTest {

    private val objectMapper = ObjectMapper()
    private val filter = AdminApiKeyFilter("admin-secret-key", objectMapper)
    private val securityConfig = SecurityConfiguration(objectMapper, "admin-secret-key")

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `AdminApiKeyFilter skips public actuator health and info endpoints`() {
        val healthReq = MockHttpServletRequest("GET", "/actuator/health")
        val infoReq = MockHttpServletRequest("GET", "/actuator/info")
        val livenessReq = MockHttpServletRequest("GET", "/actuator/health/liveness")

        filter.doFilter(healthReq, MockHttpServletResponse(), MockFilterChain())
        filter.doFilter(infoReq, MockHttpServletResponse(), MockFilterChain())
        filter.doFilter(livenessReq, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `AdminApiKeyFilter enforces X-Admin-Key on protected actuator endpoints`() {
        val missingKeyResp = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/metrics"), missingKeyResp, MockFilterChain())
        assertEquals(HttpStatus.UNAUTHORIZED.value(), missingKeyResp.status)

        val invalidKeyReq = MockHttpServletRequest("GET", "/actuator/metrics").apply {
            addHeader(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "wrong-key")
        }
        val invalidKeyResp = MockHttpServletResponse()
        filter.doFilter(invalidKeyReq, invalidKeyResp, MockFilterChain())
        assertEquals(HttpStatus.FORBIDDEN.value(), invalidKeyResp.status)

        val validKeyReq = MockHttpServletRequest("GET", "/actuator/metrics").apply {
            addHeader(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "admin-secret-key")
        }
        val validKeyResp = MockHttpServletResponse()
        filter.doFilter(validKeyReq, validKeyResp, MockFilterChain())
        assertEquals(HttpStatus.OK.value(), validKeyResp.status)
        val auth = SecurityContextHolder.getContext().authentication
        assertTrue(auth?.authorities?.any { it.authority == "ROLE_ADMIN" } == true)
    }

    @Test
    fun `SecurityConfiguration passwordEncoder entryPoint and accessDeniedHandler work as expected`() {
        val encoder = securityConfig.passwordEncoder()
        val encoded = encoder.encode("Password123!")
        assertTrue(encoder.matches("Password123!", encoded))
        assertFalse(encoder.matches("wrong", encoded))

        val unauthResp = MockHttpServletResponse()
        securityConfig.authenticationEntryPoint().commence(
            MockHttpServletRequest("GET", "/api/auth/login"),
            unauthResp,
            BadCredentialsException("Authentication required"),
        )
        assertEquals(HttpStatus.UNAUTHORIZED.value(), unauthResp.status)
        assertTrue(unauthResp.contentAsString.contains("Unauthorized"))

        val forbiddenResp = MockHttpServletResponse()
        securityConfig.accessDeniedHandler().handle(
            MockHttpServletRequest("GET", "/api/user/settings"),
            forbiddenResp,
            AccessDeniedException("Access denied"),
        )
        assertEquals(HttpStatus.FORBIDDEN.value(), forbiddenResp.status)
        assertTrue(forbiddenResp.contentAsString.contains("Forbidden"))
    }

    @Test
    fun `HttpEmailNotificationSender wraps connection errors in EmailNotificationException`() {
        val props = EmailNotificationProperties(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test-key",
        )
        val sender = HttpEmailNotificationSender(props, RestClient.builder())

        assertThrows(EmailNotificationException::class.java) {
            sender.send(
                EmailNotification(
                    to = "alice@example.com",
                    subject = "Welcome",
                    content = "Hello",
                ),
            )
        }
    }
}
