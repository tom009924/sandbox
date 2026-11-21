package com.example.todo.infrastructure.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_GRPC_PORT = 9091
private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "identity.service")
data class IdentityServiceProperties(
    val enabled: Boolean = false,
    val grpcHost: String = "localhost",
    val grpcPort: Int = DEFAULT_GRPC_PORT,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val internalApiKey: String = "internal-secret-key",
)
