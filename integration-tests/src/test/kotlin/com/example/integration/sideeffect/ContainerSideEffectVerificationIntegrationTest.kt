package com.example.integration.sideeffect

import com.example.integration.api.ContainerApiClient
import com.example.integration.api.ContainerApiClient.HTTP_CREATED
import com.example.integration.api.ContainerApiClient.HTTP_NO_CONTENT
import com.example.integration.api.ContainerApiClient.HTTP_OK
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Verifies side effects across Spanner (`spanner-proxy`), WireMock, and Pub/Sub Emulator
 * through the containerized `api-gateway` -> `identity-service` / `todo-service` stack.
 */
class ContainerSideEffectVerificationIntegrationTest {

    private val verifier = CompositeSideEffectVerifier(
        initialAllowedTables = setOf("idempotency_records"),
    )

    @BeforeEach
    fun setUp() {
        SpannerDatabaseCleaner.cleanAll()
        verifier.startTracking()
    }

    @AfterEach
    fun tearDown() {
        verifier.stopTracking()
    }

    @Test
    fun registrationProducesVerifiedSpannerAndWireMockSideEffects() {
        val username = "sideeffect_${UUID.randomUUID().toString().take(SHORT_ID_LEN)}"
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)

        // 未検証の副作用（users / user_settings / WireMock メール送信）が検知されること
        val error = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(error.message.orEmpty().contains("Unverified"))

        verifier.verifyDatabase(DbOperation.INSERT, "users")
        verifier.verifyHttpRequest("POST", "/api/v1/emails")
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun todoLifecycleProducesVerifiedSpannerAndPubSubSideEffects() {
        verifier.stopTracking()
        val (_, token) = ContainerApiClient.registerAndLogin("todo_sideeffect_user")
        verifier.startTracking()

        // 1. Create
        val todoId = UUID.fromString(
            given()
                .baseUri(ContainerTestConfig.gatewayBaseUrl)
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                .body("""{"title":"SideEffect Todo","priority":"HIGH"}""")
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HTTP_CREATED)
                .extract()
                .path<String>("id"),
        )
        verifier.verifyDatabase(DbOperation.INSERT, "todos")
        verifier.verifyPubSub("CREATED", todoId)
        verifier.assertNoUnverifiedSideEffects()

        // 2. Update
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"title":"Updated SideEffect Todo","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
        verifier.verifyDatabase(DbOperation.UPDATE, "todos")
        verifier.verifyPubSub("UPDATED", todoId)
        verifier.assertNoUnverifiedSideEffects()

        // 3. Delete
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
        verifier.verifyDatabase(DbOperation.DELETE, "todos")
        verifier.verifyPubSub("DELETED", todoId)
        verifier.assertNoUnverifiedSideEffects()
    }

    private companion object {
        const val SHORT_ID_LEN = 8
    }
}
