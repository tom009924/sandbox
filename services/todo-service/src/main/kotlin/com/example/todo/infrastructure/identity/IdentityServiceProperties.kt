package com.example.todo.infrastructure.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_GRPC_PORT = 9091
private const val DEFAULT_TIMEOUT_SECONDS = 5L
private const val DEFAULT_KEEP_ALIVE_TIME_SECONDS = 30L
private const val DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS = 10L

@ConfigurationProperties(prefix = "identity.service")
data class IdentityServiceProperties(
    val enabled: Boolean = true,
    val grpcHost: String = "localhost",
    val grpcPort: Int = DEFAULT_GRPC_PORT,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val internalApiKey: String = "internal-secret-key",
    val useTls: Boolean = false,
    val keepAliveTime: Duration = Duration.ofSeconds(DEFAULT_KEEP_ALIVE_TIME_SECONDS),
    val keepAliveTimeout: Duration = Duration.ofSeconds(DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS),
)
