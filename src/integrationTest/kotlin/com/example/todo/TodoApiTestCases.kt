package com.example.todo

import io.swagger.v3.oas.models.PathItem
import java.util.UUID

/**
 * Represents an acceptance criterion mapped directly to an OpenAPI operation and response status code.
 */
data class AcceptanceCriterion(
    val operationId: String,
    val statusCode: Int,
    val summary: String,
    val scenarios: List<String>,
)

/**
 * Parameterized test case for OpenAPI 400 Bad Request validation error scenarios.
 */
data class ValidationErrorTestCase(
    val operationId: String,
    val description: String,
    val method: PathItem.HttpMethod,
    val pathProvider: (createdId: UUID?) -> String,
    val requestBody: String,
    val needsExistingTodo: Boolean = false,
) {
    override fun toString(): String = "[$operationId] $description"
}

/**
 * Parameterized test case for OpenAPI 404 Not Found scenarios.
 */
data class NotFoundTestCase(
    val operationId: String,
    val description: String,
    val method: PathItem.HttpMethod,
    val requestBody: String? = null,
) {
    override fun toString(): String = "[$operationId] $description"
}

/**
 * Central specification and acceptance criteria definitions for Todo API integration tests.
 * Separated from test execution infrastructure for straightforward code reviews and OAS alignment.
 */
object TodoApiTestCases {

    /**
     * Complete mapping of OpenAPI operation responses to acceptance criteria and test scenarios.
     */
    val acceptanceCriteria: List<AcceptanceCriterion> = listOf(
        AcceptanceCriterion(
            operationId = "registerUser",
            statusCode = 201,
            summary = "User registered successfully",
            scenarios = listOf(
                "Register new user with valid username and password",
            ),
        ),
        AcceptanceCriterion(
            operationId = "registerUser",
            statusCode = 400,
            summary = "Invalid registration request",
            scenarios = listOf(
                "Blank username",
                "Blank password",
                "Missing username",
                "Missing password",
            ),
        ),
        AcceptanceCriterion(
            operationId = "registerUser",
            statusCode = 409,
            summary = "Username already exists",
            scenarios = listOf(
                "Conflict when username is already registered",
            ),
        ),
        AcceptanceCriterion(
            operationId = "loginUser",
            statusCode = 200,
            summary = "Authentication successful",
            scenarios = listOf(
                "Authenticate with valid username and password and receive Bearer JWT",
            ),
        ),
        AcceptanceCriterion(
            operationId = "loginUser",
            statusCode = 400,
            summary = "Invalid login request",
            scenarios = listOf(
                "Missing username",
                "Missing password",
            ),
        ),
        AcceptanceCriterion(
            operationId = "loginUser",
            statusCode = 401,
            summary = "Invalid credentials",
            scenarios = listOf(
                "Rejection when password or username does not match",
            ),
        ),
        AcceptanceCriterion(
            operationId = "listTodos",
            statusCode = 200,
            summary = "Todo list",
            scenarios = listOf(
                "Empty list when no todos exist",
                "Stored todos when records exist",
            ),
        ),
        AcceptanceCriterion(
            operationId = "listTodos",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "createTodo",
            statusCode = 201,
            summary = "Todo created",
            scenarios = listOf(
                "Basic creation with defaults, asserting database record and Pub/Sub event values",
                "Replay original response for same Idempotency-Key and suppress duplicate event",
                "Creation with full metadata, asserting database record and Pub/Sub event values",
                "Boundary minimum estimatedMinutes (0), asserting database record and Pub/Sub event values",
                "All priority enum values (LOW, MEDIUM, HIGH), asserting database record and Pub/Sub event values",
            ),
        ),
        AcceptanceCriterion(
            operationId = "createTodo",
            statusCode = 400,
            summary = "Invalid request body or parameters",
            scenarios = listOf(
                "Blank title",
                "Missing title",
                "Invalid priority",
                "Negative estimatedMinutes",
            ),
        ),
        AcceptanceCriterion(
            operationId = "createTodo",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "createTodo",
            statusCode = 409,
            summary = "Idempotency-Key conflict",
            scenarios = listOf(
                "Conflict when Idempotency-Key is reused with different request payload",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getTodo",
            statusCode = 200,
            summary = "Todo found",
            scenarios = listOf(
                "Retrieve matching existing todo with metadata",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getTodo",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getTodo",
            statusCode = 404,
            summary = "Todo not found",
            scenarios = listOf(
                "Retrieve missing todo returns 404",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateTodo",
            statusCode = 200,
            summary = "Todo updated",
            scenarios = listOf(
                "Update stored todo, clear optional fields, asserting database record and Pub/Sub event values",
                "All priority enum values (LOW, MEDIUM, HIGH), asserting database record and Pub/Sub event values",
                "Boundary minimum estimatedMinutes (0), asserting database record and Pub/Sub event values",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateTodo",
            statusCode = 400,
            summary = "Invalid request body",
            scenarios = listOf(
                "Blank title",
                "Missing title",
                "Missing completed",
                "Negative estimatedMinutes",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateTodo",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateTodo",
            statusCode = 404,
            summary = "Todo not found",
            scenarios = listOf(
                "Update missing todo returns 404",
            ),
        ),
        AcceptanceCriterion(
            operationId = "patchTodo",
            statusCode = 200,
            summary = "Todo updated",
            scenarios = listOf(
                "Partially update stored todo, asserting database record and Pub/Sub event values",
                "All priority enum values (LOW, MEDIUM, HIGH), asserting database record and Pub/Sub event values",
                "Boundary minimum estimatedMinutes (0), asserting database record and Pub/Sub event values",
            ),
        ),
        AcceptanceCriterion(
            operationId = "patchTodo",
            statusCode = 400,
            summary = "Invalid request body",
            scenarios = listOf(
                "Blank title",
                "Negative estimatedMinutes",
            ),
        ),
        AcceptanceCriterion(
            operationId = "patchTodo",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "patchTodo",
            statusCode = 404,
            summary = "Todo not found",
            scenarios = listOf(
                "Patch missing todo returns 404",
            ),
        ),
        AcceptanceCriterion(
            operationId = "deleteTodo",
            statusCode = 204,
            summary = "Todo deleted",
            scenarios = listOf(
                "Delete stored todo, verify removal from database, and publish DELETED event",
            ),
        ),
        AcceptanceCriterion(
            operationId = "deleteTodo",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "deleteTodo",
            statusCode = 404,
            summary = "Todo not found",
            scenarios = listOf(
                "Delete missing todo returns 404",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getUserSettings",
            statusCode = 200,
            summary = "User settings retrieved",
            scenarios = listOf(
                "Retrieve settings for authenticated user, returning defaults when unconfigured",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getUserSettings",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateUserSettings",
            statusCode = 200,
            summary = "User settings updated",
            scenarios = listOf(
                "Update settings for authenticated user",
                "All priority enum values (LOW, MEDIUM, HIGH)",
                "Boundary minimum defaultEstimatedMinutes (0)",
                "Boundary minimum maxActiveTodos (1)",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateUserSettings",
            statusCode = 400,
            summary = "Invalid request body",
            scenarios = listOf(
                "Missing defaultPriority",
                "Negative defaultEstimatedMinutes",
                "Zero maxActiveTodos",
            ),
        ),
        AcceptanceCriterion(
            operationId = "updateUserSettings",
            statusCode = 401,
            summary = "Missing or invalid Bearer authentication token",
            scenarios = listOf(
                "Reject unauthenticated request without token",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminStats",
            statusCode = 200,
            summary = "System statistics retrieved",
            scenarios = listOf(
                "Retrieve system statistics with valid admin key",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminStats",
            statusCode = 401,
            summary = "Missing or invalid X-Admin-Key header",
            scenarios = listOf(
                "Reject unauthenticated request without admin key",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminStats",
            statusCode = 403,
            summary = "Access denied",
            scenarios = listOf(
                "Reject request with invalid X-Admin-Key",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminUsers",
            statusCode = 200,
            summary = "Registered users retrieved successfully",
            scenarios = listOf(
                "Retrieve all registered users with valid admin key",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminUsers",
            statusCode = 401,
            summary = "Missing or invalid X-Admin-Key header",
            scenarios = listOf(
                "Reject unauthenticated request without admin key",
            ),
        ),
        AcceptanceCriterion(
            operationId = "getAdminUsers",
            statusCode = 403,
            summary = "Access denied",
            scenarios = listOf(
                "Reject request with invalid X-Admin-Key",
            ),
        ),
    )

    val acceptanceCriteriaMap: Map<Pair<String, Int>, List<String>>
        get() = acceptanceCriteria.associate { (it.operationId to it.statusCode) to it.scenarios }

    @JvmStatic
    fun validationErrorTestCases(): List<ValidationErrorTestCase> =
        postValidationErrorCases() +
            putValidationErrorCases() +
            patchValidationErrorCases() +
            authValidationErrorCases()

    private fun authValidationErrorCases(): List<ValidationErrorTestCase> = listOf(
        ValidationErrorTestCase(
            operationId = "registerUser",
            description = "Blank username",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/register" },
            requestBody = """{"username":"   ","password":"password123"}""",
        ),
        ValidationErrorTestCase(
            operationId = "registerUser",
            description = "Blank password",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/register" },
            requestBody = """{"username":"testuser","password":"   "}""",
        ),
        ValidationErrorTestCase(
            operationId = "registerUser",
            description = "Missing username",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/register" },
            requestBody = """{"password":"password123"}""",
        ),
        ValidationErrorTestCase(
            operationId = "registerUser",
            description = "Missing password",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/register" },
            requestBody = """{"username":"testuser"}""",
        ),
        ValidationErrorTestCase(
            operationId = "loginUser",
            description = "Missing username",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/login" },
            requestBody = """{"password":"password123"}""",
        ),
        ValidationErrorTestCase(
            operationId = "loginUser",
            description = "Missing password",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/auth/login" },
            requestBody = """{"username":"testuser"}""",
        ),
    )

    private fun postValidationErrorCases(): List<ValidationErrorTestCase> = listOf(
        ValidationErrorTestCase(
            operationId = "createTodo",
            description = "Blank title",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/todos" },
            requestBody = """{"title":"  "}""",
        ),
        ValidationErrorTestCase(
            operationId = "createTodo",
            description = "Missing title",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/todos" },
            requestBody = "{}",
        ),
        ValidationErrorTestCase(
            operationId = "createTodo",
            description = "Invalid priority",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/todos" },
            requestBody = """{"title":"Buy milk","priority":"URGENT"}""",
        ),
        ValidationErrorTestCase(
            operationId = "createTodo",
            description = "Negative estimatedMinutes",
            method = PathItem.HttpMethod.POST,
            pathProvider = { "/api/todos" },
            requestBody = """{"title":"Buy milk","estimatedMinutes":-1}""",
        ),
    )

    private fun putValidationErrorCases(): List<ValidationErrorTestCase> = listOf(
        ValidationErrorTestCase(
            operationId = "updateTodo",
            description = "Blank title",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"title":"  ","completed":true}""",
            needsExistingTodo = true,
        ),
        ValidationErrorTestCase(
            operationId = "updateTodo",
            description = "Missing title",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"completed":true}""",
            needsExistingTodo = true,
        ),
        ValidationErrorTestCase(
            operationId = "updateTodo",
            description = "Missing completed",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"title":"Milk bought"}""",
            needsExistingTodo = true,
        ),
        ValidationErrorTestCase(
            operationId = "updateTodo",
            description = "Negative estimatedMinutes",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"title":"Buy milk","completed":true,"estimatedMinutes":-1}""",
            needsExistingTodo = true,
        ),
        ValidationErrorTestCase(
            operationId = "updateUserSettings",
            description = "Missing defaultPriority",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { "/api/user/settings" },
            requestBody = """{"defaultEstimatedMinutes":30}""",
        ),
        ValidationErrorTestCase(
            operationId = "updateUserSettings",
            description = "Negative defaultEstimatedMinutes",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { "/api/user/settings" },
            requestBody = """{"defaultPriority":"HIGH","defaultEstimatedMinutes":-1}""",
        ),
        ValidationErrorTestCase(
            operationId = "updateUserSettings",
            description = "Zero maxActiveTodos",
            method = PathItem.HttpMethod.PUT,
            pathProvider = { "/api/user/settings" },
            requestBody = """{"defaultPriority":"HIGH","maxActiveTodos":0}""",
        ),
    )

    private fun patchValidationErrorCases(): List<ValidationErrorTestCase> = listOf(
        ValidationErrorTestCase(
            operationId = "patchTodo",
            description = "Blank title",
            method = PathItem.HttpMethod.PATCH,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"title":"  "}""",
            needsExistingTodo = true,
        ),
        ValidationErrorTestCase(
            operationId = "patchTodo",
            description = "Negative estimatedMinutes",
            method = PathItem.HttpMethod.PATCH,
            pathProvider = { id -> "/api/todos/$id" },
            requestBody = """{"estimatedMinutes":-1}""",
            needsExistingTodo = true,
        ),
    )

    @JvmStatic
    fun notFoundTestCases(): List<NotFoundTestCase> = listOf(
        NotFoundTestCase(
            operationId = "getTodo",
            description = "Get missing todo",
            method = PathItem.HttpMethod.GET,
        ),
        NotFoundTestCase(
            operationId = "updateTodo",
            description = "Update missing todo",
            method = PathItem.HttpMethod.PUT,
            requestBody = """{"title":"Milk bought","completed":true}""",
        ),
        NotFoundTestCase(
            operationId = "patchTodo",
            description = "Patch missing todo",
            method = PathItem.HttpMethod.PATCH,
            requestBody = """{"completed":true}""",
        ),
        NotFoundTestCase(
            operationId = "deleteTodo",
            description = "Delete missing todo",
            method = PathItem.HttpMethod.DELETE,
        ),
    )

    /**
     * Generates a readable alignment report between OpenAPI responses and defined acceptance criteria.
     */
    fun generateAlignmentReport(expectedResponses: Set<OpenApiResponse>): String {
        val header = "| Operation | Method & Path | Status | Summary | Test Scenarios | Status |\n" +
            "| :--- | :--- | :--- | :--- | :--- | :--- |\n"
        val rows = expectedResponses.sortedWith(
            compareBy({ it.path }, { it.method }, { it.statusCode }),
        ).joinToString("\n") { response ->
            val scenarios = acceptanceCriteriaMap[response.operationId to response.statusCode]
            val status = if (scenarios != null) "COVERED" else "MISSING"
            val scenarioText = scenarios?.joinToString("<br>") ?: "-"
            "| `${response.operationId}` | ${response.method} `${response.path}` | " +
                "${response.statusCode} | ${response.operationId} | $scenarioText | **$status** |"
        }
        return header + rows
    }
}
