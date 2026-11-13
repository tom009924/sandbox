package com.example.todo.infrastructure.crypto

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_TIMEOUT_SECONDS = 5L
private const val DEFAULT_PORT = 8080

@ConfigurationProperties(prefix = "kms")
data class GrpcKeyManagementProperties(
    val host: String = "localhost",
    val port: Int = DEFAULT_PORT,
    val keyName: String = "projects/todo/keys/task-key",
    val enabled: Boolean = false,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
)
