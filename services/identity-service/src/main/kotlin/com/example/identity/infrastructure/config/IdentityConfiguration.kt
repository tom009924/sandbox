package com.example.identity.infrastructure.config

import com.example.identity.application.AdminUserService
import com.example.identity.application.AuthService
import com.example.identity.application.TokenIssuer
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.notification.EmailNotificationSender
import com.example.identity.domain.repository.UserRepository
import com.example.identity.domain.repository.UserSettingsRepository
import com.example.identity.domain.security.PasswordEncoder
import com.example.identity.infrastructure.notification.EmailNotificationProperties
import com.example.identity.infrastructure.notification.HttpEmailNotificationSender
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailNotificationProperties::class)
class IdentityConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailNotificationSender::class)
    fun emailNotificationSender(
        properties: EmailNotificationProperties,
    ): EmailNotificationSender = HttpEmailNotificationSender(properties)

    @Bean
    fun authService(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
        tokenIssuer: TokenIssuer,
        emailNotificationSender: EmailNotificationSender,
    ) = AuthService(
        userRepository = userRepository,
        passwordEncoder = passwordEncoder,
        tokenIssuer = tokenIssuer,
        emailNotificationSender = emailNotificationSender,
    )

    @Bean
    fun userSettingsService(
        userSettingsRepository: UserSettingsRepository,
    ) = UserSettingsService(userSettingsRepository)

    @Bean
    fun adminUserService(
        userRepository: UserRepository,
    ) = AdminUserService(userRepository)
}
