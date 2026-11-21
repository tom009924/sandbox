package com.example.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_GRPC_PORT = 9091
private const val DEFAULT_GRPC_TIMEOUT_SECONDS = 5L
private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 10L

@ConfigurationProperties(prefix = "gateway.identity")
data class IdentityGrpcProperties(
    val grpcHost: String = "localhost",
    val grpcPort: Int = DEFAULT_GRPC_PORT,
    val internalApiKey: String = "internal-secret-key",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_GRPC_TIMEOUT_SECONDS),
)

@ConfigurationProperties(prefix = "gateway.todo-service")
data class TodoServiceProperties(
    val baseUrl: String = "http://localhost:8080",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS),
)
