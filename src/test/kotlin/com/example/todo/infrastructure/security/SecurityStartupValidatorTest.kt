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
        val env = MockEnvironment().apply {
            setActiveProfiles("prod")
            setProperty("todo.grpc.enabled", "true")
            setProperty("identity.service.enabled", "true")
        }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-secure-256-bit-jwt-secret-key-for-test",
            adminApiKey = "custom-secure-admin-api-key",
            todoInternalApiKey = "custom-secure-todo-internal-api-key",
            identityInternalApiKey = "custom-secure-identity-internal-api-key",
        )

        assertDoesNotThrow {
            validator.validateSecretsOnStartup()
        }
    }

    @Test
    fun `fail fast in production profile when internal api keys are default`() {
        val env = MockEnvironment().apply {
            setActiveProfiles("prod")
            setProperty("todo.grpc.enabled", "true")
            setProperty("identity.service.enabled", "true")
        }
        val todoKeyValidator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-secure-256-bit-jwt-secret-key-for-test",
            adminApiKey = "custom-secure-admin-api-key",
            todoInternalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
        )
        val todoException = assertThrows<IllegalStateException> {
            todoKeyValidator.validateSecretsOnStartup()
        }
        assertTrue(requireNotNull(todoException.message).contains("todo.security.internal-api-key"))

        val identityKeyValidator = SecurityStartupValidator(
            environment = env,
            jwtSecret = "custom-secure-256-bit-jwt-secret-key-for-test",
            adminApiKey = "custom-secure-admin-api-key",
            todoInternalApiKey = "custom-secure-todo-internal-api-key",
            identityInternalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
        )
        val identityException = assertThrows<IllegalStateException> {
            identityKeyValidator.validateSecretsOnStartup()
        }
        assertTrue(requireNotNull(identityException.message).contains("identity.service.internal-api-key"))
    }

    @Test
    fun `fail fast in staging profile when default secrets are used`() {
        val env = MockEnvironment().apply { setActiveProfiles("staging") }
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
    fun `permit default secrets in local and test profiles`() {
        val env = MockEnvironment().apply { setActiveProfiles("local", "test") }
        val validator = SecurityStartupValidator(
            environment = env,
            jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
            adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
        )

        assertDoesNotThrow {
            validator.validateSecretsOnStartup()
        }
    }
}
