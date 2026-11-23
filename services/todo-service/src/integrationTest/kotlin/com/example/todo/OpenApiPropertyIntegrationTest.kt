package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import com.example.todo.support.IntegrationDatabaseCleaner
import com.example.todo.support.SharedEmailWireMock
import com.example.todo.support.SharedOpenApiSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.constant
import io.kotest.property.checkAll
import io.restassured.RestAssured.given
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
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

@Tag("property")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["identity.service.enabled=false"],
)
class OpenApiPropertyIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val databaseCleaner: IntegrationDatabaseCleaner,
    private val objectMapper: ObjectMapper,
) {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var authorizedSpec: RequestSpecification

    @BeforeEach
    fun setup() {
        databaseCleaner.cleanAll()
        SharedEmailWireMock.resetRequests()

        val user = userRepository.save(User(username = "pbt_user", passwordHash = "hash"))
        val userId = requireNotNull(user.id)
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, user.username))
        authorizedSpec = RequestSpecBuilder()
            .setPort(port)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(SharedOpenApiSpec.validationFilter)
            .build()
    }

    @Test
    fun validCreateTodoRequestMatchesOpenApiSpecAndRoundTrips() = runProperty {
        val schema = requireSchema("CreateTodoRequest")
        val overrides = mapOf<String, Arb<Any?>>(
            "projectId" to Arb.constant(null),
            "assigneeId" to Arb.constant(null),
        )
        val arb = OpenApiSchemaArbitrary.validObject(schema, overrides)
        var createdSinceCleanup = 0

        checkAll(PropTestConfig(iterations = validIterations), arb) { payload ->
            val createdId = postTodoAndExpectCreated(payload)
            verifyTodoRoundTrip(createdId, payload)
            createdSinceCleanup++
            if (createdSinceCleanup >= CLEANUP_BATCH_SIZE) {
                todoRepository.findAll().forEach(todoRepository::delete)
                createdSinceCleanup = 0
            }
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

        checkAll(PropTestConfig(iterations = invalidIterations), arb) { invalidPayload ->
            given()
                .spec(authorizedSpec)
                .header("Idempotency-Key", UUID.randomUUID().toString())
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

        checkAll(PropTestConfig(iterations = validIterations), arb) { payload ->
            val projectId = given()
                .spec(authorizedSpec)
                .body(objectMapper.writeValueAsString(payload))
                .`when`()
                .post("/api/projects")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("id")

            assertNotNull(projectId)
        }
    }

    private fun postTodoAndExpectCreated(payload: Map<String, Any?>): String =
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(objectMapper.writeValueAsString(payload))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path("id")

    private fun verifyTodoRoundTrip(todoId: String, payload: Map<String, Any?>) {
        val fetched = given()
            .spec(authorizedSpec)
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

    private fun requireSchema(name: String) = SharedOpenApiSpec.requireSchema(name)

    private companion object {
        const val DEFAULT_VALID_ITERATIONS = 100
        const val DEFAULT_INVALID_ITERATIONS = 200
        const val CLEANUP_BATCH_SIZE = 25
        val validIterations: Int =
            System.getenv("PBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_VALID_ITERATIONS
        val invalidIterations: Int =
            System.getenv("PBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_INVALID_ITERATIONS

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            SharedEmailWireMock.registerProperties(registry)
        }
    }
}
