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
) {
    private val logger = LoggerFactory.getLogger(SecurityStartupValidator::class.java)

    @PostConstruct
    fun validateSecretsOnStartup() {
        val activeProfiles = environment.activeProfiles.toSet()
        val isProduction = activeProfiles.any {
            it.equals("prod", ignoreCase = true) || it.equals("production", ignoreCase = true)
        }

        if (isProduction) {
            check(jwtSecret != DEFAULT_JWT_SECRET) {
                "Production profile detected but 'jwt.secret' is set to default insecure value. " +
                    "A secure 256-bit secret must be provided via environment variables."
            }
            check(adminApiKey != DEFAULT_ADMIN_API_KEY) {
                "Production profile detected but 'todo.security.admin-api-key' is set to default insecure value. " +
                    "A secure admin API key must be provided via environment variables."
            }
            logger.info("Security secret validation passed for production profile.")
        } else {
            if (jwtSecret == DEFAULT_JWT_SECRET || adminApiKey == DEFAULT_ADMIN_API_KEY) {
                logger.warn(
                    "Running with default insecure security keys (JWT secret or admin API key). " +
                        "This is acceptable for local development but prohibited in production.",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_JWT_SECRET = "default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256"
        const val DEFAULT_ADMIN_API_KEY = "admin-secret-key"
    }
}
