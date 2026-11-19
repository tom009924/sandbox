package com.example.todo.infrastructure.identity

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.util.UUID

class HttpIdentityUserRepository(
    private val restClient: RestClient,
) : UserRepository {

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
            .build(),
    )

    override fun findById(id: UUID): User? = try {
        restClient.get()
            .uri("/internal/users/{id}", id)
            .retrieve()
            .body(InternalUserDto::class.java)
            ?.toDomain()
    } catch (ex: HttpClientErrorException) {
        if (ex.statusCode == HttpStatus.NOT_FOUND) null else throw ex
    }

    override fun findAllByIdIn(ids: Collection<UUID>): List<User> {
        if (ids.isEmpty()) return emptyList()
        val body = restClient.get()
            .uri { builder ->
                builder.path("/internal/users")
                    .queryParam("ids", ids.joinToString(",") { it.toString() })
                    .build()
            }
            .retrieve()
            .body(object : ParameterizedTypeReference<List<InternalUserDto>>() {})
        return body.orEmpty().map { it.toDomain() }
    }

    override fun findAll(): List<User> {
        val body = restClient.get()
            .uri("/internal/users")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<InternalUserDto>>() {})
        return body.orEmpty().map { it.toDomain() }
    }

    override fun findByUsername(username: String): User? = try {
        restClient.get()
            .uri { builder ->
                builder.path("/internal/users/by-username")
                    .queryParam("username", username)
                    .build()
            }
            .retrieve()
            .body(InternalUserDto::class.java)
            ?.toDomain()
    } catch (ex: HttpClientErrorException) {
        if (ex.statusCode == HttpStatus.NOT_FOUND) null else throw ex
    }

    override fun existsByUsername(username: String): Boolean = findByUsername(username) != null

    override fun save(user: User): User {
        throw UnsupportedOperationException(
            "User writes are owned by identity-service; direct save is disabled when identity.service.enabled=true",
        )
    }

    override fun count(): Long {
        val response = restClient.get()
            .uri("/internal/users/count")
            .retrieve()
            .body(InternalUserCountDto::class.java)
        return response?.count ?: 0L
    }

    override fun deleteAll() {
        throw UnsupportedOperationException(
            "User writes are owned by identity-service; deleteAll is disabled when identity.service.enabled=true",
        )
    }

    private data class InternalUserDto(
        val id: UUID,
        val username: String,
    ) {
        fun toDomain() = User(
            id = id,
            username = username,
            passwordHash = "",
        )
    }

    private data class InternalUserCountDto(
        val count: Long,
    )
}
