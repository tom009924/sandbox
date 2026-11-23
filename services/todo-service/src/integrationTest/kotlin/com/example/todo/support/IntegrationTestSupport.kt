package com.example.todo.support

import com.example.todo.OpenApiValidationFilter
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectMemberRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectRepository
import com.example.todo.infrastructure.persistence.SpringDataUserSettingsRepository
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * Shared WireMock instance for integration, property, and model-based tests.
 * Reusing a single server per JVM keeps `notification.email.base-url` stable across test classes,
 * enabling Spring TestContext caching instead of booting a fresh application context per class.
 */
object SharedEmailWireMock {
    private const val TEST_API_KEY = "test-api-key"

    val server: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).apply {
            start()
            stubDefaultEmailEndpoint(this)
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    if (isRunning) {
                        stop()
                    }
                },
            )
        }
    }

    fun resetRequests() {
        server.resetRequests()
    }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("notification.email.base-url") { server.baseUrl() }
        registry.add("notification.email.api-key") { TEST_API_KEY }
    }

    private fun stubDefaultEmailEndpoint(wireMock: WireMockServer) {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/api/v1/emails"))
                .willReturn(
                    WireMock.aResponse()
                        .withStatus(HttpStatus.ACCEPTED.value())
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"msg-integration-test","status":"QUEUED"}"""),
                ),
        )
    }
}

/**
 * Shared parsed OpenAPI specification and validation filter for integration tests.
 */
object SharedOpenApiSpec {
    const val SPEC_PATH = "docs/dist/openapi.admin.yaml"

    val openAPI: OpenAPI by lazy {
        requireNotNull(
            OpenAPIV3Parser().read(
                SPEC_PATH,
                null,
                ParseOptions().apply {
                    isResolve = true
                    isResolveFully = true
                },
            ),
        ) {
            "Failed to parse OpenAPI spec from $SPEC_PATH"
        }
    }

    val validationFilter: OpenApiValidationFilter by lazy {
        OpenApiValidationFilter(openAPI)
    }

    fun requireSchema(name: String): Schema<*> =
        requireNotNull(openAPI.components?.schemas?.get(name)) {
            "Schema '$name' not found in $SPEC_PATH"
        }
}

/**
 * Centralized database cleanup helper for Spanner-backed integration tests.
 */
@Component
class IntegrationDatabaseCleaner(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
    private val springDataProjectRepository: SpringDataProjectRepository,
    private val springDataUserSettingsRepository: SpringDataUserSettingsRepository,
) {
    fun cleanAll() {
        todoRepository.findAll().forEach(todoRepository::delete)
        springDataProjectMemberRepository.deleteAll()
        springDataProjectRepository.deleteAll()
        userRepository.deleteAll()
        springDataUserSettingsRepository.deleteAll()
    }
}
