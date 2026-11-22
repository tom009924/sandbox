package com.example.todo.infrastructure.grpc

import com.example.grpc.common.InternalApiKeyGrpcInterceptor
import com.example.grpc.common.TracingGrpcServerInterceptor
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
@ConditionalOnProperty(name = ["todo.grpc.enabled"], havingValue = "true", matchIfMissing = true)
class GrpcServerLifecycle(
    private val todoGrpcService: TodoGrpcService,
    private val projectGrpcService: ProjectGrpcService,
    @param:Value("\${todo.grpc.port:9092}")
    private val grpcPort: Int,
    @param:Value("\${todo.security.internal-api-key:internal-secret-key}")
    private val internalApiKey: String,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(GrpcServerLifecycle::class.java)
    private val running = AtomicBoolean(false)
    private val healthStatusManager = HealthStatusManager()
    private var server: Server? = null

    override fun start() {
        if (running.compareAndSet(false, true)) {
            val apiKeyInterceptor = InternalApiKeyGrpcInterceptor(internalApiKey)
            val tracingInterceptor = TracingGrpcServerInterceptor()
            healthStatusManager.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING)
            server = NettyServerBuilder.forPort(grpcPort)
                .addService(ServerInterceptors.intercept(todoGrpcService, apiKeyInterceptor, tracingInterceptor))
                .addService(ServerInterceptors.intercept(projectGrpcService, apiKeyInterceptor, tracingInterceptor))
                .addService(healthStatusManager.healthService)
                .build()
                .start()
            logger.info("todo-api gRPC server started on port {}", server?.port)
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            healthStatusManager.enterTerminalState()
            server?.let { srv ->
                logger.info("Shutting down todo-api gRPC server...")
                srv.shutdown()
                if (!srv.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    srv.shutdownNow()
                }
            }
            server = null
        }
    }

    override fun isRunning(): Boolean = running.get()

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
