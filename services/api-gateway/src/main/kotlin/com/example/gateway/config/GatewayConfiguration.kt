package com.example.gateway.config

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.grpc.TodoGrpcClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
    ): IdentityGrpcClient = IdentityGrpcClient(properties)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TodoGrpcClient::class)
    fun todoGrpcClient(
        properties: TodoGrpcProperties,
    ): TodoGrpcClient = TodoGrpcClient(properties)
}
