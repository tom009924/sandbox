package com.example.todo.infrastructure.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "identity.service")
data class IdentityServiceProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "http://localhost:8081",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val internalApiKey: String = "internal-secret-key",
)
