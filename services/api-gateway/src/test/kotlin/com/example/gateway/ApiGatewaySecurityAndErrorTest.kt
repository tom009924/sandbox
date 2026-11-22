package com.example.gateway

import com.example.gateway.presentation.GatewayExceptionHandler
import com.example.gateway.security.SecurityStartupValidator
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.env.MockEnvironment

class ApiGatewaySecurityAndErrorTest {

    private val handler = GatewayExceptionHandler()

    @Test
    fun `maps gRPC status codes and hides 5xx internal descriptions`() {
        val mappings = listOf(
            Status.INVALID_ARGUMENT.withDescription("bad arg") to (HttpStatus.BAD_REQUEST to "bad arg"),
            Status.FAILED_PRECONDITION.withDescription("precondition failed") to
                (HttpStatus.BAD_REQUEST to "precondition failed"),
            Status.UNAUTHENTICATED.withDescription("invalid token") to (HttpStatus.UNAUTHORIZED to "invalid token"),
            Status.PERMISSION_DENIED.withDescription("forbidden") to (HttpStatus.FORBIDDEN to "forbidden"),
            Status.NOT_FOUND.withDescription("missing") to (HttpStatus.NOT_FOUND to "missing"),
            Status.ALREADY_EXISTS.withDescription("duplicate") to (HttpStatus.CONFLICT to "duplicate"),
            Status.DEADLINE_EXCEEDED.withDescription("db timeout detail") to
                (HttpStatus.GATEWAY_TIMEOUT to HttpStatus.GATEWAY_TIMEOUT.reasonPhrase),
            Status.UNAVAILABLE.withDescription("connection refused 10.0.0.1") to
                (HttpStatus.SERVICE_UNAVAILABLE to HttpStatus.SERVICE_UNAVAILABLE.reasonPhrase),
            Status.INTERNAL.withDescription("sql stacktrace secret") to
                (HttpStatus.INTERNAL_SERVER_ERROR to HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase),
        )

        mappings.forEach { (grpcStatus, expected) ->
            val response = handler.handleGrpcException(StatusRuntimeException(grpcStatus))
            assertEquals(expected.first, response.statusCode)
            assertEquals(expected.second, response.body?.get("message"))
        }
    }

    @Test
    fun `security startup validator allows defaults on local profiles`() {
        val validator = SecurityStartupValidator(
            environment = MockEnvironment().apply { setActiveProfiles("local") },
            jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
            adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
            internalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
            todoInternalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
        )
        assertDoesNotThrow { validator.validateSecretsOnStartup() }
    }

    @Test
    fun `security startup validator rejects default secrets on production profile`() {
        val env = MockEnvironment().apply { setActiveProfiles("prod") }

        assertThrows<IllegalStateException> {
            SecurityStartupValidator(
                environment = env,
                jwtSecret = SecurityStartupValidator.DEFAULT_JWT_SECRET,
                adminApiKey = "secure-admin",
                internalApiKey = "secure-id",
                todoInternalApiKey = "secure-todo",
            ).validateSecretsOnStartup()
        }
        assertThrows<IllegalStateException> {
            SecurityStartupValidator(
                environment = env,
                jwtSecret = "secure-jwt-secret-key-256-bits-long-value",
                adminApiKey = SecurityStartupValidator.DEFAULT_ADMIN_API_KEY,
                internalApiKey = "secure-id",
                todoInternalApiKey = "secure-todo",
            ).validateSecretsOnStartup()
        }
        assertThrows<IllegalStateException> {
            SecurityStartupValidator(
                environment = env,
                jwtSecret = "secure-jwt-secret-key-256-bits-long-value",
                adminApiKey = "secure-admin",
                internalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
                todoInternalApiKey = "secure-todo",
            ).validateSecretsOnStartup()
        }
        assertThrows<IllegalStateException> {
            SecurityStartupValidator(
                environment = env,
                jwtSecret = "secure-jwt-secret-key-256-bits-long-value",
                adminApiKey = "secure-admin",
                internalApiKey = "secure-id",
                todoInternalApiKey = SecurityStartupValidator.DEFAULT_INTERNAL_API_KEY,
            ).validateSecretsOnStartup()
        }

        assertDoesNotThrow {
            SecurityStartupValidator(
                environment = env,
                jwtSecret = "secure-jwt-secret-key-256-bits-long-value",
                adminApiKey = "secure-admin",
                internalApiKey = "secure-id",
                todoInternalApiKey = "secure-todo",
            ).validateSecretsOnStartup()
        }
    }
}
