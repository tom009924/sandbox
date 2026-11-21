package com.example.gateway.security

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
    @param:Value("\${gateway.identity.internal-api-key:internal-secret-key}")
    private val internalApiKey: String,
) {
    private val logger = LoggerFactory.getLogger(SecurityStartupValidator::class.java)

    @PostConstruct
    fun validateSecretsOnStartup() {
        val activeProfiles = environment.activeProfiles.toSet()
        val allowsDefaultSecrets = activeProfiles.isEmpty() ||
            activeProfiles.all { it.lowercase() in ALLOWED_DEFAULT_SECRET_PROFILES }

        if (!allowsDefaultSecrets) {
            check(jwtSecret != DEFAULT_JWT_SECRET) {
                "Non-local profile detected (${activeProfiles.joinToString()}) " +
                    "but 'jwt.secret' is set to default insecure value."
            }
            check(adminApiKey != DEFAULT_ADMIN_API_KEY) {
                "Non-local profile detected (${activeProfiles.joinToString()}) " +
                    "but 'todo.security.admin-api-key' is set to default insecure value."
            }
            check(internalApiKey != DEFAULT_INTERNAL_API_KEY) {
                "Non-local profile detected (${activeProfiles.joinToString()}) " +
                    "but 'gateway.identity.internal-api-key' is set to default insecure value."
            }
        } else if (jwtSecret == DEFAULT_JWT_SECRET ||
            adminApiKey == DEFAULT_ADMIN_API_KEY ||
            internalApiKey == DEFAULT_INTERNAL_API_KEY
        ) {
            logger.warn(
                "Running api-gateway with default insecure security keys. " +
                    "Acceptable for local development/testing only.",
            )
        }
    }

    companion object {
        const val DEFAULT_JWT_SECRET = "default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256"
        const val DEFAULT_ADMIN_API_KEY = "admin-secret-key"
        const val DEFAULT_INTERNAL_API_KEY = "internal-secret-key"
        private val ALLOWED_DEFAULT_SECRET_PROFILES = setOf("local", "dev", "development", "test")
    }
}
