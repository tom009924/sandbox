package com.example.integration.api

import com.example.integration.openapi.OpenApiCoverageFilter
import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import java.util.UUID

/**
 * Reusable REST helper for container-backed API integration tests.
 */
internal object ContainerApiClient {
    const val HTTP_OK = 200
    const val HTTP_CREATED = 201
    const val HTTP_NO_CONTENT = 204
    const val HTTP_BAD_REQUEST = 400
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
    const val HTTP_CONFLICT = 409

    fun validatedRequest(
        coverageFilter: OpenApiCoverageFilter,
        token: String? = null,
        adminKey: String? = null,
    ): RequestSpecification = given()
        .baseUri(ContainerTestConfig.gatewayBaseUrl)
        .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
        .let { if (adminKey != null) it.header("X-Admin-Key", adminKey) else it }
        .filter(coverageFilter)
        .filter(ContainerOpenApiSpec.validationFilter)

    fun registerAndLogin(username: String, password: String = "password123"): Pair<UUID, String> {
        val userId = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")

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

        return UUID.fromString(userId) to token
    }

    @Suppress("LongParameterList")
    fun seedTodo(
        token: String,
        title: String,
        completed: Boolean = false,
        description: String? = null,
        dueDate: String? = null,
        priority: String = "MEDIUM",
        estimatedMinutes: Int? = null,
        tags: List<String> = emptyList(),
    ): UUID {
        val body = buildMap<String, Any> {
            put("title", title)
            put("priority", priority)
            put("tags", tags)
            if (description != null) put("description", description)
            if (dueDate != null) put("dueDate", dueDate)
            if (estimatedMinutes != null) put("estimatedMinutes", estimatedMinutes)
        }
        val id = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(ContentType.JSON)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(body))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")

        if (completed) {
            given()
                .baseUri(ContainerTestConfig.gatewayBaseUrl)
                .header("Authorization", "Bearer $token")
                .contentType(ContentType.JSON)
                .body("""{"completed":true}""")
                .`when`()
                .patch("/api/todos/$id")
                .then()
                .statusCode(HTTP_OK)
        }
        return UUID.fromString(id)
    }

    fun listTodoCount(token: String): Int =
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .jsonPath()
            .getList<Any>("$")
            .size
}
