package com.example.todo.infrastructure.security

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class SecurityStartupValidatorTest {

    @Test
    fun `permit default secrets in non-production profile with warning`() {
        val env = MockEnvironment()
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
            adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
        )

        assertDoesNotThrow {
            validator.validateSecretsOnStartup()
        }
    }

    @Test
    fun `fail fast in production profile when jwt secret is default`() {
        val env = MockEnvironment().apply { setActiveProfiles("prod") }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
            adminApiKey = "custom-secure-admin-api-key",
        )

        val exception = assertThrows<IllegalStateException> {
            validator.validateSecretsOnStartup()
        }
        assertTrue(requireNotNull(exception.message).contains("jwt.secret"))
    }

    @Test
    fun `fail fast in production profile when admin api key is default`() {
        val env = MockEnvironment().apply { setActiveProfiles("production") }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-secure-256-bit-jwt-secret-key-for-test",
            adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
        )

        val exception = assertThrows<IllegalStateException> {
            validator.validateSecretsOnStartup()
        }
        assertTrue(requireNotNull(exception.message).contains("admin-api-key"))
    }

    @Test
    fun `pass validation in production profile when both secrets are secure`() {
        val env = MockEnvironment().apply { setActiveProfiles("prod") }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-secure-256-bit-jwt-secret-key-for-test",
            adminApiKey = "custom-secure-admin-api-key",
        )

        assertDoesNotThrow {
            validator.validateSecretsOnStartup()
        }
    }
}
