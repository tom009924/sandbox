package com.example.gateway.config

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.grpc.TodoGrpcClient
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    IdentityGrpcProperties::class,
    TodoGrpcProperties::class,
)
class GatewayConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IdentityGrpcClient::class)
    fun identityGrpcClient(
        properties: IdentityGrpcProperties,
    ): IdentityGrpcClient = IdentityGrpcClient(
        channel = NettyChannelBuilder.forAddress(properties.grpcHost, properties.grpcPort)
            .apply {
                if (properties.useTls) {
                    useTransportSecurity()
                } else {
                    usePlaintext()
                }
            }
            .keepAliveTime(properties.keepAliveTime.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveTimeout(properties.keepAliveTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveWithoutCalls(true)
            .build(),
        internalApiKey = properties.internalApiKey,
        timeoutMillis = properties.timeout.toMillis(),
    )

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TodoGrpcClient::class)
    fun todoGrpcClient(
        properties: TodoGrpcProperties,
    ): TodoGrpcClient = TodoGrpcClient(
        channel = NettyChannelBuilder.forAddress(properties.grpcHost, properties.grpcPort)
            .apply {
                if (properties.useTls) {
                    useTransportSecurity()
                } else {
                    usePlaintext()
                }
            }
            .keepAliveTime(properties.keepAliveTime.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveTimeout(properties.keepAliveTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveWithoutCalls(true)
            .build(),
        internalApiKey = properties.internalApiKey,
        timeoutMillis = properties.timeout.toMillis(),
    )
}
