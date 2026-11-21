package com.example.identity.infrastructure.security

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class SecurityStartupValidatorTest {
    @Test
    fun `allows default secrets when no active profile or local profile`() {
        val validator = SecurityStartupValidator(
            environment = MockEnvironment().withProperty("spring.profiles.active", "local").apply {
                setActiveProfiles("local")
            },
            jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
            adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
            internalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
        )

        assertDoesNotThrow { validator.validateSecretsOnStartup() }
    }

    @Test
    fun `rejects default internal API key on production profile`() {
        val env = MockEnvironment().apply { setActiveProfiles("prod") }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-production-secret-key-at-least-256-bits-long-123456",
            adminApiKey = "custom-admin-key",
            internalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
        )

        assertThrows(IllegalStateException::class.java) {
            validator.validateSecretsOnStartup()
        }
    }
}
