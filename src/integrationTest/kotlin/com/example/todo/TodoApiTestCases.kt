package com.example.todo

import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import io.restassured.response.ValidatableResponse
import io.swagger.v3.oas.models.PathItem
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.LocalDate
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
 * Execution context provided to each table-driven integration test case for setup and verification.
 */
class TodoTestContext(
    val todoRepository: TodoRepository,
    val pubSubReceiver: PubSubTestReceiver,
    private val requestExecutor: (
        method: PathItem.HttpMethod,
        path: String,
        headers: Map<String, String>,
        body: String?,
        invalidRequest: Boolean,
    ) -> ValidatableResponse,
) {
    var existingTodoId: UUID? = null
    var idempotencyKey: String = UUID.randomUUID().toString()
    var initialTodoCount: Int = 0
    var expectedStoredTodo: Todo? = null

    fun seedTodo(todo: Todo = Todo(title = "Buy milk")): UUID {
        val saved = todoRepository.save(todo)
        val id = requireNotNull(saved.id)
        existingTodoId = id
        return id
    }

    fun executePost(body: String, key: String = idempotencyKey): ValidatableResponse =
        requestExecutor(
            PathItem.HttpMethod.POST,
            "/api/todos",
            mapOf("Idempotency-Key" to key),
            body,
            false,
        )

    fun assertStoredTodo(id: UUID, expected: Todo) {
        val stored = requireNotNull(todoRepository.findById(id)) {
            "Expected todo with ID $id to exist in database, but was not found"
        }
        assertEquals(expected.title, stored.title)
        assertEquals(expected.completed, stored.completed)
        assertEquals(expected.description, stored.description)
        assertEquals(expected.dueDate, stored.dueDate)
        assertEquals(expected.priority, stored.priority)
        assertEquals(expected.estimatedMinutes, stored.estimatedMinutes)
    }

    fun assertEventTodo(expected: Todo, actual: Todo?) {
        val todo = requireNotNull(actual) { "Expected todo event payload, but was null" }
        assertEquals(expected.title, todo.title)
        assertEquals(expected.completed, todo.completed)
        assertEquals(expected.description, todo.description)
        assertEquals(expected.dueDate, todo.dueDate)
        assertEquals(expected.priority, todo.priority)
        assertEquals(expected.estimatedMinutes, todo.estimatedMinutes)
    }

    fun assertPublishedEvent(
        expectedType: TodoEventType,
        expectedTodoId: UUID,
        expectedTodo: Todo? = null,
        assertDetails: ((ReceivedTodoEvent) -> Unit)? = null,
    ) {
        if (!pubSubReceiver.isAvailable) return
        val events = pubSubReceiver.pullEvents(maxMessages = 1)
        assertEquals(1, events.size, "Expected 1 ${expectedType.name} event to be published")
        val received = events.first()
        assertEquals(expectedType.name, received.attributes["eventType"])
        assertEquals(expectedTodoId.toString(), received.attributes["todoId"])
        assertEquals(expectedType, received.event.eventType)
        assertEquals(expectedTodoId, received.event.todoId)
        if (expectedTodo != null) {
            assertEventTodo(expectedTodo, received.event.todo)
        }
        assertDetails?.invoke(received)
    }
}

/**
 * Unified table-driven integration test case definition.
 * Enables both static pre-implementation OpenAPI alignment checks (ATDD) and dynamic server execution.
 */
@Suppress("LongParameterList")
data class TodoApiTestCase(
    val operationId: String,
    val description: String,
    val method: PathItem.HttpMethod,
    val pathTemplate: String,
    val expectedStatus: Int,
    val requestBody: String? = null,
    val invalidRequest: Boolean = false,
    val pathProvider: (TodoTestContext) -> String = { pathTemplate },
    val headersProvider: (TodoTestContext) -> Map<String, String> = { ctx ->
        if (method == PathItem.HttpMethod.POST) {
            mapOf("Idempotency-Key" to ctx.idempotencyKey)
        } else {
            emptyMap()
        }
    },
    val setup: (TodoTestContext) -> Unit = {},
    val verifyResponse: (ValidatableResponse, TodoTestContext) -> Unit = { _, _ -> },
    val verifySideEffects: (TodoTestContext, ExtractableResponse<Response>) -> Unit = { _, _ -> },
) {
    fun samplePath(): String = pathTemplate.replace("{id}", TodoApiTestCases.MISSING_TODO_ID.toString())

    fun sampleHeaders(): Map<String, String> =
        if (method == PathItem.HttpMethod.POST) {
            mapOf("Idempotency-Key" to TodoApiTestCases.SAMPLE_IDEMPOTENCY_KEY)
        } else {
            emptyMap()
        }

    override fun toString(): String = "[$operationId $expectedStatus] $description"
}

/**
 * Central table-driven specification and acceptance criteria definitions for Todo API integration tests.
 * Separated from test execution infrastructure for pre-implementation OAS alignment (ATDD) and code reviews.
 */
@Suppress("TooManyFunctions")
object TodoApiTestCases {
    const val SHORT_WAIT_TIMEOUT_MS = 500L
    const val ESTIMATED_MINUTES = 30
    const val UPDATED_ESTIMATED_MINUTES = 45
    const val MIN_ESTIMATED_MINUTES = 0
    const val SAMPLE_IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000099"
    val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private val operationSummaries: Map<Pair<String, Int>, String> = mapOf(
        ("listTodos" to HttpStatus.OK.value()) to "Todo list",
        ("createTodo" to HttpStatus.CREATED.value()) to "Todo created",
        ("createTodo" to HttpStatus.BAD_REQUEST.value()) to "Invalid request body or parameters",
        ("createTodo" to HttpStatus.CONFLICT.value()) to "Idempotency-Key conflict",
        ("getTodo" to HttpStatus.OK.value()) to "Todo found",
        ("getTodo" to HttpStatus.NOT_FOUND.value()) to "Todo not found",
        ("updateTodo" to HttpStatus.OK.value()) to "Todo updated",
        ("updateTodo" to HttpStatus.BAD_REQUEST.value()) to "Invalid request body",
        ("updateTodo" to HttpStatus.NOT_FOUND.value()) to "Todo not found",
        ("deleteTodo" to HttpStatus.NO_CONTENT.value()) to "Todo deleted",
        ("deleteTodo" to HttpStatus.NOT_FOUND.value()) to "Todo not found",
    )

    @JvmStatic
    fun allTestCases(): List<TodoApiTestCase> =
        listTodosTestCases() +
            createTodoSuccessTestCases() +
            createTodoPriorityTestCases() +
            postValidationErrorCases() +
            createTodoConflictTestCases() +
            getTodoTestCases() +
            updateTodoSuccessTestCases() +
            updateTodoPriorityTestCases() +
            putValidationErrorCases() +
            deleteTodoTestCases() +
            notFoundTestCases()

    /**
     * Acceptance criteria derived directly from the executable test table.
     */
    val acceptanceCriteria: List<AcceptanceCriterion>
        get() = allTestCases()
            .groupBy { it.operationId to it.expectedStatus }
            .map { (key, cases) ->
                AcceptanceCriterion(
                    operationId = key.first,
                    statusCode = key.second,
                    summary = operationSummaries[key] ?: key.first,
                    scenarios = cases.map { it.description },
                )
            }

    val acceptanceCriteriaMap: Map<Pair<String, Int>, List<String>>
        get() = acceptanceCriteria.associate { (it.operationId to it.statusCode) to it.scenarios }

    private fun listTodosTestCases(): List<TodoApiTestCase> = listOf(
        TodoApiTestCase(
            operationId = "listTodos",
            description = "Empty list when no todos exist",
            method = PathItem.HttpMethod.GET,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.OK.value(),
            verifyResponse = { res, _ ->
                res.body("\$", hasSize<Any>(0))
            },
        ),
        TodoApiTestCase(
            operationId = "listTodos",
            description = "Stored todos when records exist",
            method = PathItem.HttpMethod.GET,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.OK.value(),
            setup = { ctx ->
                ctx.seedTodo(Todo(title = "Buy milk"))
            },
            verifyResponse = { res, ctx ->
                res.body("id", hasItem(requireNotNull(ctx.existingTodoId).toString()))
            },
        ),
    )

    private fun createTodoSuccessTestCases(): List<TodoApiTestCase> = listOf(
        createTodoBasicTestCase(),
        createTodoIdempotentReplayTestCase(),
        createTodoMetadataTestCase(),
        createTodoMinEstimatedMinutesTestCase(),
    )

    private fun createTodoBasicTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "createTodo",
            description = "Basic creation with defaults, asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.CREATED.value(),
            requestBody = """{"title":"  Buy milk  "}""",
            verifyResponse = { res, _ ->
                res.body("title", equalTo("Buy milk"))
                    .body("completed", equalTo(false))
                    .body("description", equalTo(null))
                    .body("dueDate", equalTo(null))
                    .body("priority", equalTo("MEDIUM"))
            },
            verifySideEffects = { ctx, extracted ->
                val createdId = UUID.fromString(extracted.path("id"))
                val expected = Todo(
                    title = "Buy milk",
                    completed = false,
                    description = null,
                    dueDate = null,
                    priority = TodoPriority.MEDIUM,
                    estimatedMinutes = null,
                )
                ctx.assertStoredTodo(createdId, expected)
                ctx.assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
            },
        )

    private fun createTodoMetadataTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "createTodo",
            description = "Creation with full metadata, asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.CREATED.value(),
            requestBody = """
                {
                  "title":"  Buy milk  ",
                  "description":"Buy before the weekend",
                  "dueDate":"2025-04-18",
                  "priority":"HIGH",
                  "estimatedMinutes":30
                }
            """.trimIndent(),
            verifyResponse = { res, _ ->
                res.body("description", equalTo("Buy before the weekend"))
                    .body("dueDate", equalTo("2025-04-18"))
                    .body("priority", equalTo("HIGH"))
                    .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
            },
            verifySideEffects = { ctx, extracted ->
                val createdId = UUID.fromString(extracted.path("id"))
                val expected = Todo(
                    title = "Buy milk",
                    completed = false,
                    description = "Buy before the weekend",
                    dueDate = LocalDate.parse("2025-04-18"),
                    priority = TodoPriority.HIGH,
                    estimatedMinutes = ESTIMATED_MINUTES,
                )
                ctx.assertStoredTodo(createdId, expected)
                ctx.assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
            },
        )

    private fun createTodoMinEstimatedMinutesTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "createTodo",
            description = "Boundary minimum estimatedMinutes (0), asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.CREATED.value(),
            requestBody = """{"title":"Buy milk","estimatedMinutes":0}""",
            verifyResponse = { res, _ ->
                res.body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
            },
            verifySideEffects = { ctx, extracted ->
                val id = UUID.fromString(extracted.path("id"))
                val expected = Todo(title = "Buy milk", completed = false, estimatedMinutes = MIN_ESTIMATED_MINUTES)
                ctx.assertStoredTodo(id, expected)
                ctx.assertPublishedEvent(TodoEventType.CREATED, id, expected)
            },
        )

    private fun createTodoIdempotentReplayTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "createTodo",
            description = "Replay original response for same Idempotency-Key and suppress duplicate event",
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.CREATED.value(),
            requestBody = """{"title":"Buy milk"}""",
            setup = { ctx ->
                ctx.initialTodoCount = ctx.todoRepository.findAll().size
                val originalId = ctx.executePost("""{"title":"Buy milk"}""", ctx.idempotencyKey)
                    .statusCode(HttpStatus.CREATED.value())
                    .extract()
                    .path<String>("id")
                val todoId = UUID.fromString(originalId)
                ctx.existingTodoId = todoId
                val initialExpected = Todo(title = "Buy milk", completed = false, priority = TodoPriority.MEDIUM)
                ctx.assertStoredTodo(todoId, initialExpected)
                if (ctx.pubSubReceiver.isAvailable) {
                    val initialEvents = ctx.pubSubReceiver.pullEvents(maxMessages = 1)
                    assertEquals(1, initialEvents.size, "Expected 1 CREATED event for initial create")
                    ctx.assertEventTodo(initialExpected, initialEvents.first().event.todo)
                    ctx.pubSubReceiver.drain()
                }
                val createdTodo = requireNotNull(ctx.todoRepository.findById(todoId))
                val modifiedTodo = createdTodo.copy(title = "Changed after creation")
                ctx.todoRepository.save(modifiedTodo)
                ctx.expectedStoredTodo = modifiedTodo
            },
            verifyResponse = { res, ctx ->
                res.body("id", equalTo(requireNotNull(ctx.existingTodoId).toString()))
                    .body("title", equalTo("Buy milk"))
            },
            verifySideEffects = { ctx, _ ->
                assertEquals(ctx.initialTodoCount + 1, ctx.todoRepository.findAll().size)
                ctx.assertStoredTodo(requireNotNull(ctx.existingTodoId), requireNotNull(ctx.expectedStoredTodo))
                if (ctx.pubSubReceiver.isAvailable) {
                    val replayedEvents = ctx.pubSubReceiver.pullEvents(
                        maxMessages = 1,
                        maxWait = Duration.ofMillis(SHORT_WAIT_TIMEOUT_MS),
                    )
                    assertEquals(
                        0,
                        replayedEvents.size,
                        "Replaying idempotent request must not publish duplicate events",
                    )
                }
            },
        )

    private fun createTodoPriorityTestCases(): List<TodoApiTestCase> =
        TodoPriority.entries.map { priority ->
            TodoApiTestCase(
                operationId = "createTodo",
                description =
                    "Priority enum value ${priority.name}, asserting database record and Pub/Sub event values",
                method = PathItem.HttpMethod.POST,
                pathTemplate = "/api/todos",
                expectedStatus = HttpStatus.CREATED.value(),
                requestBody = """{"title":"Buy milk","priority":"${priority.name}"}""",
                verifyResponse = { res, _ ->
                    res.body("priority", equalTo(priority.name))
                },
                verifySideEffects = { ctx, extracted ->
                    val id = UUID.fromString(extracted.path("id"))
                    val expected = Todo(title = "Buy milk", completed = false, priority = priority)
                    ctx.assertStoredTodo(id, expected)
                    ctx.assertPublishedEvent(TodoEventType.CREATED, id, expected)
                },
            )
        }

    private fun createTodoConflictTestCases(): List<TodoApiTestCase> = listOf(
        TodoApiTestCase(
            operationId = "createTodo",
            description = "Conflict when Idempotency-Key is reused with different request payload",
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.CONFLICT.value(),
            requestBody = """{"title":"Buy bread"}""",
            setup = { ctx ->
                ctx.initialTodoCount = ctx.todoRepository.findAll().size
                ctx.executePost("""{"title":"Buy milk"}""", ctx.idempotencyKey)
                    .statusCode(HttpStatus.CREATED.value())
            },
            verifyResponse = { res, ctx ->
                res.body(
                    "message",
                    equalTo("Idempotency key '${ctx.idempotencyKey}' was already used with a different request"),
                )
            },
            verifySideEffects = { ctx, _ ->
                assertEquals(ctx.initialTodoCount + 1, ctx.todoRepository.findAll().size)
            },
        ),
    )

    private fun getTodoTestCases(): List<TodoApiTestCase> = listOf(
        TodoApiTestCase(
            operationId = "getTodo",
            description = "Retrieve matching existing todo with metadata",
            method = PathItem.HttpMethod.GET,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.OK.value(),
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            setup = { ctx ->
                ctx.seedTodo(
                    Todo(
                        title = "Buy milk",
                        completed = true,
                        description = "Buy before the weekend",
                        dueDate = LocalDate.parse("2025-04-18"),
                        priority = TodoPriority.HIGH,
                        estimatedMinutes = ESTIMATED_MINUTES,
                    ),
                )
            },
            verifyResponse = { res, ctx ->
                res.body("id", equalTo(requireNotNull(ctx.existingTodoId).toString()))
                    .body("title", equalTo("Buy milk"))
                    .body("completed", equalTo(true))
                    .body("description", equalTo("Buy before the weekend"))
                    .body("dueDate", equalTo("2025-04-18"))
                    .body("priority", equalTo("HIGH"))
                    .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
            },
        ),
    )

    private fun updateTodoSuccessTestCases(): List<TodoApiTestCase> = listOf(
        updateTodoFullMetadataTestCase(),
        updateTodoClearOptionalFieldsTestCase(),
        updateTodoMinEstimatedMinutesTestCase(),
    )

    private fun updateTodoFullMetadataTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "updateTodo",
            description = "Update stored todo with full metadata, asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.PUT,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.OK.value(),
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            requestBody = """
                {
                  "title":"Milk bought",
                  "completed":true,
                  "description":"Purchased",
                  "dueDate":"2025-04-19",
                  "priority":"LOW",
                  "estimatedMinutes":45
                }
            """.trimIndent(),
            setup = { ctx ->
                ctx.seedTodo(Todo(title = "Buy milk"))
            },
            verifyResponse = { res, _ ->
                res.body("title", equalTo("Milk bought"))
                    .body("completed", equalTo(true))
                    .body("description", equalTo("Purchased"))
                    .body("dueDate", equalTo("2025-04-19"))
                    .body("priority", equalTo("LOW"))
                    .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))
            },
            verifySideEffects = { ctx, _ ->
                val todoId = requireNotNull(ctx.existingTodoId)
                val expected = Todo(
                    title = "Milk bought",
                    completed = true,
                    description = "Purchased",
                    dueDate = LocalDate.parse("2025-04-19"),
                    priority = TodoPriority.LOW,
                    estimatedMinutes = UPDATED_ESTIMATED_MINUTES,
                )
                ctx.assertStoredTodo(todoId, expected)
                ctx.assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
            },
        )

    private fun updateTodoClearOptionalFieldsTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "updateTodo",
            description = "Clear optional fields on update, asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.PUT,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.OK.value(),
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            requestBody = """{"title":"Milk bought","completed":true}""",
            setup = { ctx ->
                ctx.seedTodo(
                    Todo(
                        title = "Milk bought",
                        completed = true,
                        description = "Purchased",
                        dueDate = LocalDate.parse("2025-04-19"),
                        priority = TodoPriority.LOW,
                        estimatedMinutes = UPDATED_ESTIMATED_MINUTES,
                    ),
                )
            },
            verifyResponse = { res, _ ->
                res.body("description", equalTo(null))
                    .body("dueDate", equalTo(null))
                    .body("priority", equalTo("MEDIUM"))
                    .body("estimatedMinutes", equalTo(null))
            },
            verifySideEffects = { ctx, _ ->
                val todoId = requireNotNull(ctx.existingTodoId)
                val expected = Todo(title = "Milk bought", completed = true, priority = TodoPriority.MEDIUM)
                ctx.assertStoredTodo(todoId, expected)
                ctx.assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
            },
        )

    private fun updateTodoMinEstimatedMinutesTestCase(): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "updateTodo",
            description = "Boundary minimum estimatedMinutes (0), asserting database record and Pub/Sub event values",
            method = PathItem.HttpMethod.PUT,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.OK.value(),
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            requestBody = """{"title":"Buy milk","completed":true,"estimatedMinutes":0}""",
            setup = { ctx ->
                ctx.seedTodo(Todo(title = "Buy milk"))
            },
            verifyResponse = { res, _ ->
                res.body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
            },
            verifySideEffects = { ctx, _ ->
                val todoId = requireNotNull(ctx.existingTodoId)
                val expected = Todo(title = "Buy milk", completed = true, estimatedMinutes = MIN_ESTIMATED_MINUTES)
                ctx.assertStoredTodo(todoId, expected)
                ctx.assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
            },
        )

    private fun updateTodoPriorityTestCases(): List<TodoApiTestCase> =
        TodoPriority.entries.map { priority ->
            TodoApiTestCase(
                operationId = "updateTodo",
                description =
                    "Priority enum value ${priority.name}, asserting database record and Pub/Sub event values",
                method = PathItem.HttpMethod.PUT,
                pathTemplate = "/api/todos/{id}",
                expectedStatus = HttpStatus.OK.value(),
                pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
                requestBody = """{"title":"Milk bought","completed":true,"priority":"${priority.name}"}""",
                setup = { ctx ->
                    ctx.seedTodo(Todo(title = "Buy milk"))
                },
                verifyResponse = { res, _ ->
                    res.body("priority", equalTo(priority.name))
                },
                verifySideEffects = { ctx, _ ->
                    val todoId = requireNotNull(ctx.existingTodoId)
                    val expected = Todo(title = "Milk bought", completed = true, priority = priority)
                    ctx.assertStoredTodo(todoId, expected)
                    ctx.assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
                },
            )
        }

    private fun deleteTodoTestCases(): List<TodoApiTestCase> = listOf(
        TodoApiTestCase(
            operationId = "deleteTodo",
            description = "Delete stored todo, verify removal from database, and publish DELETED event",
            method = PathItem.HttpMethod.DELETE,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.NO_CONTENT.value(),
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            setup = { ctx ->
                ctx.seedTodo(Todo(title = "Buy milk"))
            },
            verifySideEffects = { ctx, _ ->
                val todoId = requireNotNull(ctx.existingTodoId)
                assertEquals(null, ctx.todoRepository.findById(todoId))
                ctx.assertPublishedEvent(TodoEventType.DELETED, todoId) {
                    assertEquals(null, it.event.todo)
                }
            },
        ),
    )

    private fun postValidationErrorCases(): List<TodoApiTestCase> = listOf(
        postValidationErrorCase("Blank title", """{"title":"  "}"""),
        postValidationErrorCase("Missing title", "{}"),
        postValidationErrorCase("Invalid priority", """{"title":"Buy milk","priority":"URGENT"}"""),
        postValidationErrorCase("Negative estimatedMinutes", """{"title":"Buy milk","estimatedMinutes":-1}"""),
    )

    private fun postValidationErrorCase(description: String, body: String): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "createTodo",
            description = description,
            method = PathItem.HttpMethod.POST,
            pathTemplate = "/api/todos",
            expectedStatus = HttpStatus.BAD_REQUEST.value(),
            requestBody = body,
            invalidRequest = true,
            verifySideEffects = { ctx, _ ->
                assertEquals(0, ctx.todoRepository.findAll().size)
            },
        )

    private fun putValidationErrorCases(): List<TodoApiTestCase> = listOf(
        putValidationErrorCase("Blank title", """{"title":"  ","completed":true}"""),
        putValidationErrorCase("Missing title", """{"completed":true}"""),
        putValidationErrorCase("Missing completed", """{"title":"Milk bought"}"""),
        putValidationErrorCase(
            "Negative estimatedMinutes",
            """{"title":"Buy milk","completed":true,"estimatedMinutes":-1}""",
        ),
    )

    private fun putValidationErrorCase(description: String, body: String): TodoApiTestCase =
        TodoApiTestCase(
            operationId = "updateTodo",
            description = description,
            method = PathItem.HttpMethod.PUT,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.BAD_REQUEST.value(),
            requestBody = body,
            invalidRequest = true,
            pathProvider = { ctx -> "/api/todos/${requireNotNull(ctx.existingTodoId)}" },
            setup = { ctx ->
                ctx.seedTodo(Todo(title = "Buy milk"))
            },
            verifySideEffects = { ctx, _ ->
                assertEquals(1, ctx.todoRepository.findAll().size)
            },
        )

    private fun notFoundTestCases(): List<TodoApiTestCase> = listOf(
        TodoApiTestCase(
            operationId = "getTodo",
            description = "Get missing todo",
            method = PathItem.HttpMethod.GET,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.NOT_FOUND.value(),
            pathProvider = { "/api/todos/$MISSING_TODO_ID" },
            verifySideEffects = { ctx, _ ->
                assertEquals(0, ctx.todoRepository.findAll().size)
            },
        ),
        TodoApiTestCase(
            operationId = "updateTodo",
            description = "Update missing todo",
            method = PathItem.HttpMethod.PUT,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.NOT_FOUND.value(),
            requestBody = """{"title":"Milk bought","completed":true}""",
            pathProvider = { "/api/todos/$MISSING_TODO_ID" },
            verifySideEffects = { ctx, _ ->
                assertEquals(0, ctx.todoRepository.findAll().size)
            },
        ),
        TodoApiTestCase(
            operationId = "deleteTodo",
            description = "Delete missing todo",
            method = PathItem.HttpMethod.DELETE,
            pathTemplate = "/api/todos/{id}",
            expectedStatus = HttpStatus.NOT_FOUND.value(),
            pathProvider = { "/api/todos/$MISSING_TODO_ID" },
            verifySideEffects = { ctx, _ ->
                assertEquals(0, ctx.todoRepository.findAll().size)
            },
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
