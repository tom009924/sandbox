package com.example.todo.infrastructure.config

import com.example.grpc.common.TracingGrpcClientInterceptor
import com.example.todo.application.AdminStatsService
import com.example.todo.application.AdminUserService
import com.example.todo.application.AuthService
import com.example.todo.application.ProjectService
import com.example.todo.application.TodoAccessChecker
import com.example.todo.application.TodoCommentService
import com.example.todo.application.TodoService
import com.example.todo.application.TodoSubtaskService
import com.example.todo.application.TokenIssuer
import com.example.todo.application.UserSettingsService
import com.example.todo.domain.crypto.CryptoService
import com.example.todo.domain.crypto.TodoCryptoHandler
import com.example.todo.domain.event.TodoEventPublisher
import com.example.todo.domain.notification.EmailNotificationSender
import com.example.todo.domain.repository.IdempotencyRecordRepository
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.ProjectRepository
import com.example.todo.domain.repository.TodoCommentRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.TodoSubtaskRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.domain.security.PasswordEncoder
import com.example.todo.infrastructure.crypto.GrpcKeyManagementClient
import com.example.todo.infrastructure.crypto.GrpcKeyManagementProperties
import com.example.todo.infrastructure.identity.GrpcIdentityUserRepository
import com.example.todo.infrastructure.identity.GrpcIdentityUserSettingsRepository
import com.example.todo.infrastructure.identity.IdentityServiceProperties
import com.example.todo.infrastructure.notification.EmailNotificationProperties
import com.example.todo.infrastructure.notification.HttpEmailNotificationSender
import com.example.todo.infrastructure.pubsub.PubSubProperties
import com.example.todo.infrastructure.pubsub.PubSubTodoEventPublisher
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(
    PubSubProperties::class,
    EmailNotificationProperties::class,
    GrpcKeyManagementProperties::class,
    IdentityServiceProperties::class,
)
@Suppress("TooManyFunctions")
class TodoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = ["identity.service.enabled"], havingValue = "true")
    fun identityGrpcChannel(
        properties: IdentityServiceProperties,
    ): ManagedChannel = ManagedChannelBuilder
        .forAddress(properties.grpcHost, properties.grpcPort)
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
        .intercept(TracingGrpcClientInterceptor())
        .build()

    @Bean
    @ConditionalOnProperty(name = ["identity.service.enabled"], havingValue = "true")
    fun grpcIdentityUserRepository(
        identityGrpcChannel: ManagedChannel,
        properties: IdentityServiceProperties,
    ): UserRepository = GrpcIdentityUserRepository(
        channel = identityGrpcChannel,
        internalApiKey = properties.internalApiKey,
        timeoutMillis = properties.timeout.toMillis(),
    )

    @Bean
    @ConditionalOnProperty(name = ["identity.service.enabled"], havingValue = "true")
    fun grpcIdentityUserSettingsRepository(
        identityGrpcChannel: ManagedChannel,
        properties: IdentityServiceProperties,
    ): UserSettingsRepository = GrpcIdentityUserSettingsRepository(
        channel = identityGrpcChannel,
        internalApiKey = properties.internalApiKey,
        timeoutMillis = properties.timeout.toMillis(),
    )

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
    @Suppress("LongParameterList")
    fun todoService(
        todoRepository: TodoRepository,
        idempotencyRecordRepository: IdempotencyRecordRepository,
        todoEventPublisher: TodoEventPublisher,
        userSettingsRepository: UserSettingsRepository,
        cryptoHandler: TodoCryptoHandler,
        projectMemberRepository: ProjectMemberRepository,
    ) = TodoService(
        todoRepository = todoRepository,
        idempotencyRecordRepository = idempotencyRecordRepository,
        todoEventPublisher = todoEventPublisher,
        userSettingsRepository = userSettingsRepository,
        cryptoHandler = cryptoHandler,
        projectMemberRepository = projectMemberRepository,
    )

    @Bean
    fun projectService(
        projectRepository: ProjectRepository,
        projectMemberRepository: ProjectMemberRepository,
        userRepository: UserRepository,
        todoRepository: TodoRepository,
    ) = ProjectService(
        projectRepository = projectRepository,
        projectMemberRepository = projectMemberRepository,
        userRepository = userRepository,
        todoRepository = todoRepository,
    )

    @Bean
    fun todoAccessChecker(
        todoRepository: TodoRepository,
        projectMemberRepository: ProjectMemberRepository,
    ) = TodoAccessChecker(
        todoRepository = todoRepository,
        projectMemberRepository = projectMemberRepository,
    )

    @Bean
    fun todoSubtaskService(
        todoAccessChecker: TodoAccessChecker,
        todoSubtaskRepository: TodoSubtaskRepository,
    ) = TodoSubtaskService(
        todoAccessChecker = todoAccessChecker,
        todoSubtaskRepository = todoSubtaskRepository,
    )

    @Bean
    fun todoCommentService(
        todoAccessChecker: TodoAccessChecker,
        todoCommentRepository: TodoCommentRepository,
        userRepository: UserRepository,
    ) = TodoCommentService(
        todoAccessChecker = todoAccessChecker,
        todoCommentRepository = todoCommentRepository,
        userRepository = userRepository,
    )

    @Bean
    @ConditionalOnProperty(name = ["identity.service.enabled"], havingValue = "false", matchIfMissing = true)
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
    @ConditionalOnProperty(name = ["identity.service.enabled"], havingValue = "false", matchIfMissing = true)
    fun authService(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
        tokenIssuer: TokenIssuer,
        emailNotificationSender: EmailNotificationSender,
    ) = AuthService(userRepository, passwordEncoder, tokenIssuer, emailNotificationSender)
}
