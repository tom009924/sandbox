package com.example.integration.smoke

import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import com.example.integration.support.WireMockAdminClient
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Smoke test verifying the full containerized microservices topology:
 * `api-gateway` -> `identity-service` & `todo-service` -> `spanner-emulator` & `wiremock`.
 */
class ContainerMicroservicesSmokeTest {

    @BeforeEach
    fun resetState() {
        SpannerDatabaseCleaner.cleanAll()
        WireMockAdminClient.resetRequests()
    }

    @Test
    fun fullMicroservicesStackHandlesAuthTodoAndAdminFlows() {
        assertTrue(ContainerOpenApiSpec.openAPI.paths.isNotEmpty())
        assertGatewayHealthy()

        val token = registerAndLogin(username = "container_smoke_user", password = "password123")
        assertEquals(1, WireMockAdminClient.countPostRequests("/api/v1/emails"))

        val todoId = createTodo(token = token, title = "Container Smoke Todo")
        assertEquals("Container Smoke Todo", fetchTodoTitle(token, todoId))
        assertEquals(1, fetchAdminTotalTodos())

        deleteTodo(token, todoId)
    }

    private fun assertGatewayHealthy() {
        val healthStatus = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .`when`()
            .get("/actuator/health")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path<String>("status")
        assertEquals("UP", healthStatus)
    }

    private fun registerAndLogin(username: String, password: String): String {
        val registeredUsername = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("username")
        assertEquals(username, registeredUsername)

        val token = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path<String>("token")
        assertNotNull(token)
        return token
    }

    private fun createTodo(token: String, title: String): String {
        val todoId = given()
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
            .path<String>("id")
        assertNotNull(todoId)
        return todoId
    }

    private fun fetchTodoTitle(token: String, todoId: String): String =
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path("title")

    private fun fetchAdminTotalTodos(): Int =
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("X-Admin-Api-Key", ContainerTestConfig.adminApiKey)
            .`when`()
            .get("/api/admin/stats")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path("totalTodos")

    private fun deleteTodo(token: String, todoId: String) {
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NO_CONTENT = 204
    }
}
