package com.example.todo.infrastructure.config

import com.example.todo.application.AdminStatsService
import com.example.todo.application.AdminUserService
import com.example.todo.application.AuthService
import com.example.todo.application.TokenIssuer
import com.example.todo.application.TodoService
import com.example.todo.application.UserSettingsService
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.notification.EmailNotificationSender
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.domain.security.PasswordEncoder
import com.example.todo.infrastructure.notification.EmailNotificationProperties
import com.example.todo.infrastructure.notification.HttpEmailNotificationSender
import com.example.todo.infrastructure.pubsub.PubSubProperties
import com.example.todo.infrastructure.pubsub.PubSubTodoEventPublisher
import com.example.todo.domain.crypto.CryptoService
import com.example.todo.domain.crypto.TodoCryptoHandler
import com.example.todo.infrastructure.crypto.GrpcKeyManagementClient
import com.example.todo.infrastructure.crypto.GrpcKeyManagementProperties
import tools.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(
    PubSubProperties::class,
    EmailNotificationProperties::class,
    GrpcKeyManagementProperties::class,
)
class TodoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CryptoService::class)
    fun cryptoService(
        kmsProperties: GrpcKeyManagementProperties,
    ): CryptoService? = if (kmsProperties.enabled) {
        GrpcKeyManagementClient(kmsProperties)
    } else {
        null
    }

    @Bean
    @ConditionalOnMissingBean(EmailNotificationSender::class)
    fun emailNotificationSender(
        properties: EmailNotificationProperties,
    ): EmailNotificationSender = HttpEmailNotificationSender(properties)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TodoEventPublisher::class)
    fun todoEventPublisher(
        pubSubProperties: PubSubProperties,
        objectMapper: ObjectMapper,
    ): TodoEventPublisher = PubSubTodoEventPublisher(pubSubProperties, objectMapper)

    @Bean
    fun todoCryptoHandler(
        cryptoService: CryptoService?,
        kmsProperties: GrpcKeyManagementProperties,
    ): TodoCryptoHandler = TodoCryptoHandler(cryptoService, kmsProperties.keyName)

    @Bean
    fun todoService(
        todoRepository: TodoRepository,
        idempotencyRecordRepository: IdempotencyRecordRepository,
        todoEventPublisher: TodoEventPublisher,
        userSettingsRepository: UserSettingsRepository,
        cryptoHandler: TodoCryptoHandler,
    ) = TodoService(
        todoRepository = todoRepository,
        idempotencyRecordRepository = idempotencyRecordRepository,
        todoEventPublisher = todoEventPublisher,
        userSettingsRepository = userSettingsRepository,
        cryptoHandler = cryptoHandler,
    )

    @Bean
    fun userSettingsService(
        userSettingsRepository: UserSettingsRepository,
    ) = UserSettingsService(userSettingsRepository)
    @Bean
    fun adminStatsService(
        userRepository: UserRepository,
        todoRepository: TodoRepository,
    ) = AdminStatsService(userRepository, todoRepository)

    @Bean
    fun adminUserService(
        userRepository: UserRepository,
    ) = AdminUserService(userRepository)

    @Bean
    fun authService(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
        tokenIssuer: TokenIssuer,
        emailNotificationSender: EmailNotificationSender,
    ) = AuthService(userRepository, passwordEncoder, tokenIssuer, emailNotificationSender)
}
