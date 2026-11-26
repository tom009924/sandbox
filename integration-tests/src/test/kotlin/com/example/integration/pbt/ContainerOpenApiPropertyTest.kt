package com.example.integration.pbt

import com.example.integration.openapi.OpenApiSchemaArbitrary
import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import com.example.integration.support.WireMockAdminClient
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * OpenAPI spec-driven property-based tests executed against the real containerized
 * `api-gateway` -> `identity-service` / `todo-service` stack.
 */
@Tag("property")
class ContainerOpenApiPropertyTest {

    @BeforeEach
    fun setup() {
        SpannerDatabaseCleaner.cleanPreservingUser(pbtUserId)
        WireMockAdminClient.resetRequests()
    }

    @Test
    fun validCreateTodoRequestMatchesOpenApiSpecAndRoundTrips() = runProperty {
        val schema = ContainerOpenApiSpec.requireSchema("CreateTodoRequest")
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
                SpannerDatabaseCleaner.cleanTodosOnly()
                createdSinceCleanup = 0
            }
        }
    }

    @Test
    fun invalidCreateTodoRequestReturnsBadRequest() = runProperty {
        val schema = ContainerOpenApiSpec.requireSchema("CreateTodoRequest")
        val overrides = mapOf<String, Arb<Any?>>(
            "projectId" to Arb.constant(null),
            "assigneeId" to Arb.constant(null),
        )
        val arb = OpenApiSchemaArbitrary.invalidObject(schema, overrides)

        checkAll(PropTestConfig(iterations = invalidIterations), arb) { invalidPayload ->
            given()
                .spec(authorizedSpec)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(invalidPayload))
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HTTP_BAD_REQUEST)
        }
    }

    @Test
    fun validCreateProjectRequestMatchesOpenApiSpecAndRoundTrips() = runProperty {
        val schema = ContainerOpenApiSpec.requireSchema("CreateProjectRequest")
        val arb = OpenApiSchemaArbitrary.validObject(schema)
        var createdSinceCleanup = 0

        checkAll(PropTestConfig(iterations = validIterations), arb) { payload ->
            val projectId = given()
                .spec(authorizedSpec)
                .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(payload))
                .`when`()
                .post("/api/projects")
                .then()
                .statusCode(HTTP_CREATED)
                .extract()
                .path<String>("id")

            assertNotNull(projectId)
            createdSinceCleanup++
            if (createdSinceCleanup >= CLEANUP_BATCH_SIZE) {
                SpannerDatabaseCleaner.cleanProjectsAndTodos()
                createdSinceCleanup = 0
            }
        }
    }

    private fun postTodoAndExpectCreated(payload: Map<String, Any?>): String =
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(payload))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path("id")

    private fun verifyTodoRoundTrip(todoId: String, payload: Map<String, Any?>) {
        val fetched = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
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

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val DEFAULT_VALID_ITERATIONS = 50
        const val DEFAULT_INVALID_ITERATIONS = 100
        const val CLEANUP_BATCH_SIZE = 25
        val validIterations: Int =
            System.getenv("PBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_VALID_ITERATIONS
        val invalidIterations: Int =
            System.getenv("PBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_INVALID_ITERATIONS
        lateinit var pbtUserId: UUID
        lateinit var authorizedSpec: RequestSpecification

        @JvmStatic
        @BeforeAll
        fun setupPbtUser() {
            SpannerDatabaseCleaner.cleanAll()
            WireMockAdminClient.resetRequests()

            val userId = given()
                .baseUri(ContainerTestConfig.gatewayBaseUrl)
                .contentType(ContentType.JSON)
                .body("""{"username":"pbt_user","password":"password123"}""")
                .`when`()
                .post("/api/auth/register")
                .then()
                .statusCode(HTTP_CREATED)
                .extract()
                .path<String>("id")

            val token = given()
                .baseUri(ContainerTestConfig.gatewayBaseUrl)
                .contentType(ContentType.JSON)
                .body("""{"username":"pbt_user","password":"password123"}""")
                .`when`()
                .post("/api/auth/login")
                .then()
                .statusCode(HTTP_OK)
                .extract()
                .path<String>("token")

            pbtUserId = UUID.fromString(userId)
            authorizedSpec = RequestSpecBuilder()
                .setBaseUri(ContainerTestConfig.gatewayBaseUrl)
                .addHeader("Authorization", "Bearer $token")
                .setContentType(ContentType.JSON)
                .addFilter(ContainerOpenApiSpec.validationFilter)
                .build()
        }
    }
}
