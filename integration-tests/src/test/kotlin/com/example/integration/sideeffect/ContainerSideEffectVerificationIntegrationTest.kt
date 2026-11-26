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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Verifies side effects across Spanner (`spanner-proxy`), WireMock, and Pub/Sub Emulator
 * through the containerized `api-gateway` -> `identity-service` / `todo-service` stack.
 */
@Suppress("TooManyFunctions")
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
        val token = registerUntrackedUser("todo_sideeffect_user")

        // 1. Create
        val todoId = createTodoAs(token, "SideEffect Todo")
        verifier.verifyDatabase(DbOperation.INSERT, "todos")
        val createdEvent = verifier.verifyPubSub("CREATED", todoId)
        assertNotNull(createdEvent.eventId)
        assertEquals("SideEffect Todo", createdEvent.todoTitle)
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
        val updatedEvent = verifier.verifyPubSub("UPDATED", todoId)
        assertNotNull(updatedEvent.eventId)
        assertEquals("Updated SideEffect Todo", updatedEvent.todoTitle)
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
        val deletedEvent = verifier.verifyPubSub("DELETED", todoId)
        assertNotNull(deletedEvent.eventId)
        assertNull(deletedEvent.todoTitle)
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun userSettingsUpdateProducesVerifiedSpannerSideEffectWithoutExternalNoise() {
        val token = registerUntrackedUser("settings_sideeffect_user")

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":25,"maxActiveTodos":5}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)

        verifier.verifyDatabase(DbOperation.UPSERT, "user_settings")
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun projectAndMemberLifecycleProducesVerifiedSpannerSideEffects() {
        verifier.stopTracking()
        val (_, ownerToken) = ContainerApiClient.registerAndLogin("project_owner_se")
        val (memberId, _) = ContainerApiClient.registerAndLogin("project_member_se")
        verifier.startTracking()

        val projectId = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $ownerToken")
            .contentType(ContentType.JSON)
            .body("""{"name":"SideEffect Project","description":"Initial"}""")
            .`when`()
            .post("/api/projects")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        verifier.verifyDatabase(DbOperation.INSERT, "projects")
        verifier.verifyDatabase(DbOperation.INSERT, "project_members")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $ownerToken")
            .contentType(ContentType.JSON)
            .body("""{"username":"project_member_se","role":"MEMBER"}""")
            .`when`()
            .post("/api/projects/$projectId/members")
            .then()
            .statusCode(HTTP_CREATED)
        verifier.verifyDatabase(DbOperation.INSERT, "project_members")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $ownerToken")
            .contentType(ContentType.JSON)
            .body("""{"name":"Renamed SideEffect Project","description":"Updated"}""")
            .`when`()
            .put("/api/projects/$projectId")
            .then()
            .statusCode(HTTP_OK)
        verifier.verifyDatabase(DbOperation.UPDATE, "projects")
        verifier.assertNoUnverifiedSideEffects()

        removeMemberAndDeleteProject(ownerToken, projectId, memberId)
    }

    @Test
    fun todoSubtaskAndCommentLifecycleProducesVerifiedSpannerSideEffects() {
        verifier.stopTracking()
        val (_, token) = ContainerApiClient.registerAndLogin("collab_sideeffect_user")
        val todoId = ContainerApiClient.seedTodo(token, title = "Parent Todo")
        verifier.startTracking()

        val subtaskId = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"title":"Subtask 1"}""")
            .`when`()
            .post("/api/todos/$todoId/subtasks")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        verifier.verifyDatabase(DbOperation.INSERT, "todo_subtasks")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"completed":true}""")
            .`when`()
            .patch("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HTTP_OK)
        verifier.verifyDatabase(DbOperation.UPDATE, "todo_subtasks")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .delete("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
        verifier.verifyDatabase(DbOperation.DELETE, "todo_subtasks")
        verifier.assertNoUnverifiedSideEffects()

        verifyCommentLifecycleSideEffects(token, todoId)
    }

    private fun registerUntrackedUser(username: String): String {
        verifier.stopTracking()
        val (_, token) = ContainerApiClient.registerAndLogin(username)
        verifier.startTracking()
        return token
    }

    private fun createTodoAs(token: String, title: String): UUID =
        UUID.fromString(
            given()
                .baseUri(ContainerTestConfig.gatewayBaseUrl)
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                .body("""{"title":"$title","priority":"HIGH"}""")
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HTTP_CREATED)
                .extract()
                .path<String>("id"),
        )

    private fun removeMemberAndDeleteProject(ownerToken: String, projectId: String, memberId: UUID) {
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $ownerToken")
            .`when`()
            .delete("/api/projects/$projectId/members/$memberId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
        verifier.verifyDatabase(DbOperation.DELETE, "project_members")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $ownerToken")
            .`when`()
            .delete("/api/projects/$projectId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
        verifier.verifyDatabase(DbOperation.DELETE, "project_members")
        verifier.verifyDatabase(DbOperation.DELETE, "projects")
        verifier.assertNoUnverifiedSideEffects()
    }

    private fun verifyCommentLifecycleSideEffects(token: String, todoId: UUID) {
        val commentId = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"content":"SideEffect comment"}""")
            .`when`()
            .post("/api/todos/$todoId/comments")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        verifier.verifyDatabase(DbOperation.INSERT, "todo_comments")
        verifier.assertNoUnverifiedSideEffects()

        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .delete("/api/todos/$todoId/comments/$commentId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
        verifier.verifyDatabase(DbOperation.DELETE, "todo_comments")
        verifier.assertNoUnverifiedSideEffects()
    }

    private companion object {
        const val SHORT_ID_LEN = 8
    }
}
