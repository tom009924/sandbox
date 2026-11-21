package com.example.todo.infrastructure.identity

import com.example.todo.domain.model.TodoPriority
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

@WireMockTest
class HttpIdentityClientWireMockTest {

    @Test
    fun `HttpIdentityUserRepository fetches user, list, username, and count with internal API key`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        val userId = UUID.randomUUID()
        val internalKey = "test-internal-key"
        val properties = IdentityServiceProperties(
            enabled = true,
            baseUrl = wmRuntimeInfo.httpBaseUrl,
            internalApiKey = internalKey,
        )
        val repository = HttpIdentityUserRepository(properties)

        stubFor(
            get(urlEqualTo("/internal/users/$userId"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"$userId","username":"alice"}"""),
                ),
        )
        stubFor(
            get(urlEqualTo("/internal/users/by-username?username=alice"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"$userId","username":"alice"}"""),
                ),
        )
        stubFor(
            get(urlEqualTo("/internal/users?ids=$userId"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id":"$userId","username":"alice"}]"""),
                ),
        )
        stubFor(
            get(urlEqualTo("/internal/users/count"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"count":42}"""),
                ),
        )

        assertEquals("alice", repository.findById(userId)?.username)
        assertEquals(userId, repository.findByUsername("alice")?.id)
        assertEquals(listOf("alice"), repository.findAllByIdIn(listOf(userId, userId)).map { it.username })
        assertEquals(42L, repository.count())
    }

    @Test
    fun `HttpIdentityUserRepository returns null on 404`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        val missingId = UUID.randomUUID()
        val repository = HttpIdentityUserRepository(
            IdentityServiceProperties(enabled = true, baseUrl = wmRuntimeInfo.httpBaseUrl),
        )

        stubFor(
            get(urlEqualTo("/internal/users/$missingId"))
                .willReturn(aResponse().withStatus(404)),
        )

        assertNull(repository.findById(missingId))
    }

    @Test
    fun `HttpIdentityUserSettingsRepository fetches user settings and returns null on 404`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        val userId = UUID.randomUUID()
        val missingUserId = UUID.randomUUID()
        val internalKey = "test-internal-key"
        val repository = HttpIdentityUserSettingsRepository(
            IdentityServiceProperties(
                enabled = true,
                baseUrl = wmRuntimeInfo.httpBaseUrl,
                internalApiKey = internalKey,
            ),
        )

        stubFor(
            get(urlEqualTo("/internal/users/$userId/settings"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "userId": "$userId",
                              "defaultPriority": "HIGH",
                              "defaultEstimatedMinutes": 30,
                              "maxActiveTodos": 10
                            }
                            """.trimIndent(),
                        ),
                ),
        )
        stubFor(
            get(urlEqualTo("/internal/users/$missingUserId/settings"))
                .withHeader(HttpIdentityUserRepository.INTERNAL_API_KEY_HEADER, equalTo(internalKey))
                .willReturn(aResponse().withStatus(404)),
        )

        val settings = repository.findByUserId(userId)

        assertEquals(TodoPriority.HIGH, settings?.defaultPriority)
        assertEquals(30, settings?.defaultEstimatedMinutes)
        assertEquals(10, settings?.maxActiveTodos)
        assertNull(repository.findByUserId(missingUserId))
    }
}
