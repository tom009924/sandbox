package com.example.todo.infrastructure.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "notification.email")
data class EmailNotificationProperties(
    val baseUrl: String = "http://localhost:8089",
    val apiKey: String = "test-api-key",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
)
