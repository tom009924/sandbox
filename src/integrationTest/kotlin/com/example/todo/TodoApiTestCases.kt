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
            operationId = "listTodos",
            statusCode = 200,
            summary = "Todo list",
            scenarios = listOf(
                "Empty list when no todos exist",
                "Stored todos when records exist",
            ),
        ),
        AcceptanceCriterion(
            operationId = "createTodo",
            statusCode = 201,
            summary = "Todo created",
            scenarios = listOf(
                "Basic creation with defaults",
                "Replay original response for same Idempotency-Key",
                "Creation with full metadata (description, dueDate, priority, estimatedMinutes)",
                "Boundary minimum estimatedMinutes (0)",
                "All priority enum values (LOW, MEDIUM, HIGH)",
                "Publish CREATED event to Pub/Sub with todo payload",
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
                "Update stored todo and clear optional fields",
                "All priority enum values (LOW, MEDIUM, HIGH)",
                "Boundary minimum estimatedMinutes (0)",
                "Publish UPDATED event to Pub/Sub with updated todo payload",
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
            statusCode = 404,
            summary = "Todo not found",
            scenarios = listOf(
                "Update missing todo returns 404",
            ),
        ),
        AcceptanceCriterion(
            operationId = "deleteTodo",
            statusCode = 204,
            summary = "Todo deleted",
            scenarios = listOf(
                "Delete stored todo and verify removal from database",
                "Publish DELETED event to Pub/Sub with null todo payload",
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
    )

    val acceptanceCriteriaMap: Map<Pair<String, Int>, List<String>>
        get() = acceptanceCriteria.associate { (it.operationId to it.statusCode) to it.scenarios }

    @JvmStatic
    fun validationErrorTestCases(): List<ValidationErrorTestCase> =
        postValidationErrorCases() + putValidationErrorCases()

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
