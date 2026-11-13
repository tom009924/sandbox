package com.example.todo.infrastructure.crypto

import java.time.Duration

private const val DEFAULT_TIMEOUT_SECONDS = 5L

data class GrpcKeyManagementProperties(
    val host: String,
    val port: Int,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
)
