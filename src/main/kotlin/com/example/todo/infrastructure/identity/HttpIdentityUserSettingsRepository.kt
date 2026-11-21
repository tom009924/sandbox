package com.example.todo.infrastructure.identity

import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.UserSettingsRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.util.UUID

class HttpIdentityUserSettingsRepository(
    private val restClient: RestClient,
) : UserSettingsRepository {

    constructor(
        properties: IdentityServiceProperties,
        restClientBuilder: RestClient.Builder = RestClient.builder(),
    ) : this(
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(properties.timeout)
                    setReadTimeout(properties.timeout)
                },
            )
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, properties.internalApiKey)
            .build(),
    )

    override fun findByUserId(userId: UUID): UserSettings? = try {
        restClient.get()
            .uri("/internal/users/{id}/settings", userId)
            .retrieve()
            .body(InternalUserSettingsDto::class.java)
            ?.toDomain()
    } catch (ex: HttpClientErrorException) {
        if (ex.statusCode == HttpStatus.NOT_FOUND) null else throw ex
    }

    override fun save(userSettings: UserSettings): UserSettings {
        throw UnsupportedOperationException(
            "UserSettings writes are owned by identity-service; " +
                "direct save is disabled when identity.service.enabled=true",
        )
    }

    private data class InternalUserSettingsDto(
        val userId: UUID,
        val defaultPriority: TodoPriority = TodoPriority.MEDIUM,
        val defaultEstimatedMinutes: Int? = null,
        val maxActiveTodos: Int? = null,
    ) {
        fun toDomain() = UserSettings(
            userId = userId,
            defaultPriority = defaultPriority,
            defaultEstimatedMinutes = defaultEstimatedMinutes,
            maxActiveTodos = maxActiveTodos,
        )
    }
}
