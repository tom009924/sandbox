package com.example.todo.infrastructure.security

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class SecurityStartupValidator(
    private val environment: Environment,
    @param:Value("\${jwt.secret:default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private val jwtSecret: String,
    @param:Value("\${todo.security.admin-api-key:admin-secret-key}")
    private val adminApiKey: String,
    @param:Value("\${todo.security.internal-api-key:internal-secret-key}")
    private val todoInternalApiKey: String = DEFAULT_INTERNAL_API_KEY,
    @param:Value("\${identity.service.internal-api-key:internal-secret-key}")
    private val identityInternalApiKey: String = DEFAULT_INTERNAL_API_KEY,
) {
    private val logger = LoggerFactory.getLogger(SecurityStartupValidator::class.java)
    private val todoGrpcEnabled: Boolean
        get() = environment.getProperty("todo.grpc.enabled", Boolean::class.java, true)
    private val identityServiceEnabled: Boolean
        get() = environment.getProperty("identity.service.enabled", Boolean::class.java, false)

    @PostConstruct
    fun validateSecretsOnStartup() {
        val activeProfiles = environment.activeProfiles.toSet()
        val allowsDefaultSecrets = activeProfiles.isEmpty() ||
            activeProfiles.all { it.lowercase() in ALLOWED_DEFAULT_SECRET_PROFILES }

        if (!allowsDefaultSecrets) {
            validateNonLocalSecrets(activeProfiles)
            logger.info("Security secret validation passed for profiles: ${activeProfiles.joinToString()}.")
        } else if (hasAnyDefaultSecret()) {
            logger.warn(
                "Running with default insecure security keys. " +
                    "This is acceptable for local development/testing only.",
            )
        }
    }

    private fun validateNonLocalSecrets(activeProfiles: Set<String>) {
        val profileList = activeProfiles.joinToString()
        check(jwtSecret != DEFAULT_JWT_SECRET) {
            "Non-local profile detected ($profileList) " +
                "but 'jwt.secret' is set to default insecure value. " +
                "A secure 256-bit secret must be provided via environment variables."
        }
        check(adminApiKey != DEFAULT_ADMIN_API_KEY) {
            "Non-local profile detected ($profileList) " +
                "but 'todo.security.admin-api-key' is set to default insecure value. " +
                "A secure admin API key must be provided via environment variables."
        }
        if (todoGrpcEnabled) {
            check(todoInternalApiKey != DEFAULT_INTERNAL_API_KEY) {
                "Non-local profile detected ($profileList) " +
                    "but 'todo.security.internal-api-key' is set to default insecure value. " +
                    "A secure internal API key must be provided via environment variables."
            }
        }
        if (identityServiceEnabled) {
            check(identityInternalApiKey != DEFAULT_INTERNAL_API_KEY) {
                "Non-local profile detected ($profileList) " +
                    "but 'identity.service.internal-api-key' is set to default insecure value. " +
                    "A secure internal API key must be provided via environment variables."
            }
        }
    }

    private fun hasAnyDefaultSecret(): Boolean =
        jwtSecret == DEFAULT_JWT_SECRET ||
            adminApiKey == DEFAULT_ADMIN_API_KEY ||
            (todoGrpcEnabled && todoInternalApiKey == DEFAULT_INTERNAL_API_KEY) ||
            (identityServiceEnabled && identityInternalApiKey == DEFAULT_INTERNAL_API_KEY)

    companion object {
        const val DEFAULT_JWT_SECRET = "default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256"
        const val DEFAULT_ADMIN_API_KEY = "admin-secret-key"
        const val DEFAULT_INTERNAL_API_KEY = "internal-secret-key"
        private val ALLOWED_DEFAULT_SECRET_PROFILES = setOf("local", "dev", "development", "test")
    }
}
