package com.example.identity.infrastructure.grpc

import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
@ConditionalOnProperty(name = ["identity.grpc.enabled"], havingValue = "true", matchIfMissing = true)
class GrpcServerLifecycle(
    private val identityGrpcService: IdentityGrpcService,
    @param:Value("\${identity.grpc.port:9091}")
    private val grpcPort: Int,
    @param:Value("\${identity.security.internal-api-key:internal-secret-key}")
    private val internalApiKey: String,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(GrpcServerLifecycle::class.java)
    private val running = AtomicBoolean(false)
    private var server: Server? = null

    override fun start() {
        if (running.compareAndSet(false, true)) {
            val interceptedService = ServerInterceptors.intercept(
                identityGrpcService,
                InternalApiKeyGrpcInterceptor(internalApiKey),
            )
            server = NettyServerBuilder.forPort(grpcPort)
                .addService(interceptedService)
                .build()
                .start()
            logger.info("identity-service gRPC server started on port {}", server?.port)
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            server?.let { srv ->
                logger.info("Shutting down identity-service gRPC server...")
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
