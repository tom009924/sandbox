package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectMemberRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.constant
import io.kotest.property.checkAll
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiPropertyIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
    private val springDataProjectRepository: SpringDataProjectRepository,
    private val objectMapper: ObjectMapper,
) {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var defaultToken: String

    @BeforeEach
    fun setup() {
        todoRepository.findAll().forEach(todoRepository::delete)
        springDataProjectMemberRepository.deleteAll()
        springDataProjectRepository.deleteAll()
        userRepository.deleteAll()
        wireMockServer.resetRequests()

        val user = userRepository.save(User(username = "pbt_user", passwordHash = "hash"))
        val userId = requireNotNull(user.id)
        defaultToken = jwtTokenProvider.generateToken(AuthenticatedUser(userId, user.username))
    }

    @Test
    fun validCreateTodoRequestMatchesOpenApiSpecAndRoundTrips() = runProperty {
        val schema = requireSchema("CreateTodoRequest")
        val overrides = mapOf<String, Arb<Any?>>(
            "projectId" to Arb.constant(null),
            "assigneeId" to Arb.constant(null),
        )
        val arb = OpenApiSchemaArbitrary.validObject(schema, overrides)

        checkAll(PropTestConfig(iterations = ITERATIONS), arb) { payload ->
            val createdId = postTodoAndExpectCreated(payload)
            verifyTodoRoundTrip(createdId, payload)
            deleteTodo(createdId)
        }
    }

    @Test
    fun invalidCreateTodoRequestReturnsBadRequest() = runProperty {
        val schema = requireSchema("CreateTodoRequest")
        val overrides = mapOf<String, Arb<Any?>>(
            "projectId" to Arb.constant(null),
            "assigneeId" to Arb.constant(null),
        )
        val arb = OpenApiSchemaArbitrary.invalidObject(schema, overrides)

        checkAll(PropTestConfig(iterations = ITERATIONS), arb) { invalidPayload ->
            given()
                .port(port)
                .header("Authorization", "Bearer $defaultToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .filter(openApiValidationFilter)
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(invalidPayload))
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
        }
    }

    @Test
    fun validCreateProjectRequestMatchesOpenApiSpecAndRoundTrips() = runProperty {
        val schema = requireSchema("CreateProjectRequest")
        val arb = OpenApiSchemaArbitrary.validObject(schema)

        checkAll(PropTestConfig(iterations = ITERATIONS), arb) { payload ->
            val projectId = given()
                .port(port)
                .header("Authorization", "Bearer $defaultToken")
                .filter(openApiValidationFilter)
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(payload))
                .`when`()
                .post("/api/projects")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("id")

            assertNotNull(projectId)

            given()
                .port(port)
                .header("Authorization", "Bearer $defaultToken")
                .filter(openApiValidationFilter)
                .`when`()
                .delete("/api/projects/$projectId")
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value())
        }
    }

    private fun postTodoAndExpectCreated(payload: Map<String, Any?>): String =
        given()
            .port(port)
            .header("Authorization", "Bearer $defaultToken")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .filter(openApiValidationFilter)
            .contentType(ContentType.JSON)
            .body(objectMapper.writeValueAsString(payload))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path("id")

    private fun verifyTodoRoundTrip(todoId: String, payload: Map<String, Any?>) {
        val fetched = given()
            .port(port)
            .header("Authorization", "Bearer $defaultToken")
            .filter(openApiValidationFilter)
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .extract()
            .jsonPath()

        assertEquals(payload["title"].toString().trim(), fetched.getString("title"))
        if (payload.containsKey("description")) {
            assertEquals(payload["description"], fetched.getString("description"))
        }
        if (payload.containsKey("priority") && payload["priority"] != null) {
            assertEquals(payload["priority"], fetched.getString("priority"))
        }
    }

    private fun deleteTodo(todoId: String) {
        given()
            .port(port)
            .header("Authorization", "Bearer $defaultToken")
            .filter(openApiValidationFilter)
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())
    }

    private fun runProperty(block: suspend () -> Unit) {
        val latch = CountDownLatch(1)
        var outcome: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    outcome = result
                    latch.countDown()
                }
            },
        )
        latch.await()
        outcome?.getOrThrow()
    }

    private fun requireSchema(name: String) =
        requireNotNull(openAPI.components?.schemas?.get(name)) {
            "Schema '$name' not found in $OPEN_API_SPEC_PATH"
        }

    private companion object {
        const val ITERATIONS = 100
        const val OPEN_API_SPEC_PATH = "docs/dist/openapi.admin.yaml"

        val openAPI = requireNotNull(
            OpenAPIV3Parser().read(
                OPEN_API_SPEC_PATH,
                null,
                ParseOptions().apply {
                    isResolve = true
                    isResolveFully = true
                },
            ),
        ) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val openApiValidationFilter = OpenApiValidationFilter(openAPI)

        private val wireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply {
            start()
            stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/emails"))
                    .willReturn(
                        WireMock.aResponse()
                            .withStatus(HttpStatus.ACCEPTED.value())
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"id":"msg-pbt","status":"QUEUED"}"""),
                    ),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.email.base-url") { wireMockServer.baseUrl() }
            registry.add("notification.email.api-key") { "test-api-key" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }
}
