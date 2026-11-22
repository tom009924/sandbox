package com.example.identity.infrastructure.grpc

import com.example.grpc.common.TracingGrpcServerInterceptor
import com.example.identity.application.AdminUserService
import com.example.identity.application.AuthService
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.repository.UserRepository
import com.example.identity.grpc.v1.CountUsersRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import io.opentelemetry.api.trace.Span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicReference

class TracingAndGrpcLifecycleTest {

    @Test
    fun `extracts W3C traceparent and maps unexpected errors to INTERNAL`() {
        val authService = mock(AuthService::class.java)
        val userSettingsService = mock(UserSettingsService::class.java)
        val adminUserService = mock(AdminUserService::class.java)
        val userRepository = mock(UserRepository::class.java)

        val capturedTraceId = AtomicReference<String>()
        `when`(userRepository.count()).thenAnswer {
            capturedTraceId.set(Span.current().spanContext.traceId)
            error("unexpected db crash")
        }

        val service = IdentityGrpcService(authService, userSettingsService, adminUserService, userRepository)
        val serverName = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(service, TracingGrpcServerInterceptor()))
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        try {
            val headers = Metadata().apply {
                put(
                    Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER),
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                )
            }
            val stub = IdentityServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))

            val ex = assertThrows<StatusRuntimeException> {
                stub.countUsers(CountUsersRequest.getDefaultInstance())
            }
            assertEquals(Status.Code.INTERNAL, ex.status.code)
            assertEquals("Internal server error", ex.status.description)
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", capturedTraceId.get())
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `grpc server lifecycle exposes standard health service and shuts down cleanly`() {
        val service = IdentityGrpcService(
            mock(AuthService::class.java),
            mock(UserSettingsService::class.java),
            mock(AdminUserService::class.java),
            mock(UserRepository::class.java),
        )
        val lifecycle = GrpcServerLifecycle(
            identityGrpcService = service,
            grpcPort = 0,
            internalApiKey = "test-key",
        )
        assertFalse(lifecycle.isRunning)
        lifecycle.start()
        assertTrue(lifecycle.isRunning)
        try {
            // Verify health service can be queried when running on ephemeral port
            val serverField = GrpcServerLifecycle::class.java.getDeclaredField("server").apply { isAccessible = true }
            val server = serverField.get(lifecycle) as io.grpc.Server
            val channel = io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1", server.port)
                .usePlaintext()
                .build()
            try {
                val health = HealthGrpc.newBlockingStub(channel)
                    .check(HealthCheckRequest.getDefaultInstance())
                assertEquals(HealthCheckResponse.ServingStatus.SERVING, health.status)
            } finally {
                channel.shutdownNow()
            }
        } finally {
            lifecycle.stop()
            assertFalse(lifecycle.isRunning)
        }
    }
}
