package com.example.gateway.config

import com.example.gateway.grpc.IdentityGrpcClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    IdentityGrpcProperties::class,
    TodoServiceProperties::class,
)
class GatewayConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IdentityGrpcClient::class)
    fun identityGrpcClient(
        properties: IdentityGrpcProperties,
    ): IdentityGrpcClient = IdentityGrpcClient(properties)

    @Bean
    fun todoServiceRestClient(
        properties: TodoServiceProperties,
        restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
    ): RestClient = restClientBuilderProvider
        .getIfAvailable { RestClient.builder() }
        .baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.timeout)
                setReadTimeout(properties.timeout)
            },
        )
        .build()
}
