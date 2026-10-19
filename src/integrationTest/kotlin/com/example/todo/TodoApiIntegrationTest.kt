package com.example.todo

import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import com.example.todo.domain.event.TodoEventType
import com.example.todo.infrastructure.pubsub.PubSubProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Suppress("TooManyFunctions")
class TodoApiIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val pubSubReceiver: PubSubTestReceiver,
) {
    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun cleanDatabase() {
        todoRepository.findAll().forEach(todoRepository::delete)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }
    }

    @Test
    fun createTodoReturnsCreatedTodo() {
        val response = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .extract()

        val createdId = UUID.fromString(response.path("id"))
        val expected = Todo(
            title = "Buy milk",
            completed = false,
            description = null,
            dueDate = null,
            priority = TodoPriority.MEDIUM,
            estimatedMinutes = null,
        )
        assertStoredTodo(createdId, expected)
        assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
    }

    @Test
    fun createTodoReplaysOriginalResponseForSameIdempotencyKey() {
        val idempotencyKey = UUID.randomUUID().toString()
        val initialTodoCount = todoRepository.findAll().size

        val originalId = validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path<String>("id")

        val todoId = UUID.fromString(originalId)
        val initialExpected = Todo(title = "Buy milk", completed = false, priority = TodoPriority.MEDIUM)
        assertStoredTodo(todoId, initialExpected)

        if (pubSubReceiver.isAvailable) {
            val initialEvents = pubSubReceiver.pullEvents(maxMessages = 1)
            assertEquals(1, initialEvents.size, "Expected 1 CREATED event for initial create")
            assertEventTodo(initialExpected, initialEvents.first().event.todo)
            pubSubReceiver.drain()
        }

        val createdTodo = requireNotNull(todoRepository.findById(todoId))
        val modifiedTodo = createdTodo.copy(title = "Changed after creation")
        todoRepository.save(modifiedTodo)

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("id", equalTo(originalId))
            .body("title", equalTo("Buy milk"))

        assertEquals(initialTodoCount + 1, todoRepository.findAll().size)
        assertStoredTodo(todoId, modifiedTodo)

        if (pubSubReceiver.isAvailable) {
            val replayedEvents = pubSubReceiver.pullEvents(
                maxMessages = 1,
                maxWait = Duration.ofMillis(SHORT_WAIT_TIMEOUT_MS),
            )
            assertEquals(0, replayedEvents.size, "Replaying idempotent request must not publish duplicate events")
        }
    }

    @Test
    fun createTodoReturnsConflictWhenIdempotencyKeyIsReusedForDifferentRequest() {
        val idempotencyKey = UUID.randomUUID().toString()
        val initialTodoCount = todoRepository.findAll().size

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy bread"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CONFLICT.value())
            .body("message", equalTo("Idempotency key '$idempotencyKey' was already used with a different request"))

        assertEquals(initialTodoCount + 1, todoRepository.findAll().size)
    }

    @Test
    fun createTodoWithMetadataReturnsCreatedTodo() {
        val response = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title":"  Buy milk  ",
                  "description":"Buy before the weekend",
                  "dueDate":"2025-04-18",
                  "priority":"HIGH",
                  "estimatedMinutes":30
                }
                """.trimIndent(),
            )
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
            .extract()

        val createdId = UUID.fromString(response.path("id"))
        val expected = Todo(
            title = "Buy milk",
            completed = false,
            description = "Buy before the weekend",
            dueDate = LocalDate.parse("2025-04-18"),
            priority = TodoPriority.HIGH,
            estimatedMinutes = ESTIMATED_MINUTES,
        )
        assertStoredTodo(createdId, expected)
        assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
    }

    @Test
    fun createAndUpdateTodoAcceptEstimatedMinutesMinimum() {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","estimatedMinutes":0}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
            .extract()
            .path<String>("id")

        val id = UUID.fromString(todoId)
        val expectedCreate = Todo(title = "Buy milk", completed = false, estimatedMinutes = MIN_ESTIMATED_MINUTES)
        assertStoredTodo(id, expectedCreate)
        assertPublishedEvent(TodoEventType.CREATED, id, expectedCreate)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":0}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))

        val expectedUpdate = Todo(title = "Buy milk", completed = true, estimatedMinutes = MIN_ESTIMATED_MINUTES)
        assertStoredTodo(id, expectedUpdate)
        assertPublishedEvent(TodoEventType.UPDATED, id, expectedUpdate)
    }

    @ParameterizedTest(name = "Priority: {0}")
    @EnumSource(TodoPriority::class)
    fun createAndUpdateTodoAcceptEveryPriorityValue(priority: TodoPriority) {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","priority":"${priority.name}"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("priority", equalTo(priority.name))
            .extract()
            .path<String>("id")

        val id = UUID.fromString(todoId)
        val expectedCreate = Todo(title = "Buy milk", completed = false, priority = priority)
        assertStoredTodo(id, expectedCreate)
        assertPublishedEvent(TodoEventType.CREATED, id, expectedCreate)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true,"priority":"${priority.name}"}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("priority", equalTo(priority.name))

        val expectedUpdate = Todo(title = "Milk bought", completed = true, priority = priority)
        assertStoredTodo(id, expectedUpdate)
        assertPublishedEvent(TodoEventType.UPDATED, id, expectedUpdate)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#validationErrorTestCases")
    fun validationErrorsReturnBadRequest(tc: ValidationErrorTestCase) {
        val targetId = if (tc.needsExistingTodo) {
            val todo = todoRepository.save(Todo(title = "Buy milk"))
            requireNotNull(todo.id)
        } else {
            null
        }
        val expectedCount = if (tc.needsExistingTodo) 1 else 0

        val request = if (tc.method == PathItem.HttpMethod.POST) {
            validatedPostRequest(invalidRequest = true)
        } else {
            validatedRequest(invalidRequest = true)
        }

        request
            .contentType(ContentType.JSON)
            .body(tc.requestBody)
            .`when`()
            .request(tc.method.name, tc.pathProvider(targetId))
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        assertEquals(expectedCount, todoRepository.findAll().size)
    }

    @Test
    fun listTodosReturnsEmptyListWhenNoTodosExist() {
        validatedRequest()
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("\$", hasSize<Any>(0))
    }

    @Test
    fun listTodosReturnsStoredTodos() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))

        validatedRequest()
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", hasItem(requireNotNull(todo.id).toString()))
    }

    @Test
    fun getTodoReturnsMatchingTodo() {
        val dueDate = LocalDate.parse("2025-04-18")
        val todo = todoRepository.save(
            Todo(
                title = "Buy milk",
                completed = true,
                description = "Buy before the weekend",
                dueDate = dueDate,
                priority = TodoPriority.HIGH,
                estimatedMinutes = 30,
            ),
        )
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", equalTo(todoId.toString()))
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#notFoundTestCases")
    fun missingTodoReturnsNotFound(tc: NotFoundTestCase) {
        val request = validatedRequest()
        if (tc.requestBody != null) {
            request.contentType(ContentType.JSON).body(tc.requestBody)
        }

        request
            .`when`()
            .request(tc.method.name, "/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        assertEquals(0, todoRepository.findAll().size)
    }

    @Test
    fun verifyAllOasOperationsHaveAcceptanceCriteriaInTests() {
        val oasExpectedResponses = coverageDefinition.operationResponses
        val testDefinedOperations = TodoApiTestCases.acceptanceCriteriaMap.keys
        val missing = oasExpectedResponses - testDefinedOperations
        assertTrue(
            missing.isEmpty(),
            "The following OAS operation responses do not have corresponding acceptance criteria in tests: " +
                missing.joinToString { "${it.first} -> ${it.second}" },
        )
    }

    @Test
    fun updateTodoChangesStoredTodo() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title":"Milk bought",
                  "completed":true,
                  "description":"Purchased",
                  "dueDate":"2025-04-19",
                  "priority":"LOW",
                  "estimatedMinutes":45
                }
                """.trimIndent(),
            )
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Purchased"))
            .body("dueDate", equalTo("2025-04-19"))
            .body("priority", equalTo("LOW"))
            .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))

        val expectedFirst = Todo(
            title = "Milk bought",
            completed = true,
            description = "Purchased",
            dueDate = LocalDate.parse("2025-04-19"),
            priority = TodoPriority.LOW,
            estimatedMinutes = UPDATED_ESTIMATED_MINUTES,
        )
        assertStoredTodo(todoId, expectedFirst)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expectedFirst)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .body("estimatedMinutes", equalTo(null))

        val expectedSecond = Todo(title = "Milk bought", completed = true, priority = TodoPriority.MEDIUM)
        assertStoredTodo(todoId, expectedSecond)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expectedSecond)
    }

    @Test
    fun deleteTodoRemovesStoredTodo() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        assertEquals(null, todoRepository.findById(todoId))

        assertPublishedEvent(TodoEventType.DELETED, todoId) {
            assertEquals(null, it.event.todo)
        }
    }

    private fun assertStoredTodo(id: UUID, expected: Todo) {
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

    private fun assertEventTodo(expected: Todo, actual: Todo?) {
        val todo = requireNotNull(actual) { "Expected todo event payload, but was null" }
        assertEquals(expected.title, todo.title)
        assertEquals(expected.completed, todo.completed)
        assertEquals(expected.description, todo.description)
        assertEquals(expected.dueDate, todo.dueDate)
        assertEquals(expected.priority, todo.priority)
        assertEquals(expected.estimatedMinutes, todo.estimatedMinutes)
    }

    private fun assertPublishedEvent(
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

    @TestConfiguration
    class PubSubTestConfiguration {
        @Bean(destroyMethod = "close")
        fun pubSubTestReceiver(
            pubSubProperties: PubSubProperties,
            objectMapper: ObjectMapper,
        ): PubSubTestReceiver =
            PubSubTestReceiver(
                projectId = pubSubProperties.projectId,
                topicId = pubSubProperties.topicId,
                emulatorHost = pubSubProperties.emulatorHost,
                objectMapper = objectMapper,
            )
    }


    private fun validatedRequest(
        invalidRequest: Boolean = false,
    ) =
        given()
            .port(port)
            .filter(openApiCoverageFilter)
            .filter(if (invalidRequest) invalidRequestFilter else openApiValidationFilter)

    private fun validatedPostRequest(
        invalidRequest: Boolean = false,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = validatedRequest(invalidRequest)
        .header("Idempotency-Key", idempotencyKey)

    private companion object {
        const val SHORT_WAIT_TIMEOUT_MS = 500L
        const val ESTIMATED_MINUTES = 30
        const val UPDATED_ESTIMATED_MINUTES = 45
        const val MIN_ESTIMATED_MINUTES = 0
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val OPEN_API_SPEC_PATH = "docs/openapi.yaml"

        val openAPI = requireNotNull(OpenAPIV3Parser().read(OPEN_API_SPEC_PATH)) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val coverageDefinition = OpenApiCoverageDefinition.from(openAPI)
        val openApiCoverageFilter = OpenApiCoverageFilter(coverageDefinition)

        val openApiValidationFilter = OpenApiValidationFilter(openAPI)
        val invalidRequestFilter = OpenApiValidationFilter(openAPI, validateRequest = false)

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiResponsesCovered() {
            val missingResponses = coverageDefinition.responses - openApiCoverageFilter.coveredResponses
            assertTrue(
                missingResponses.isEmpty(),
                "The following OpenAPI responses were not exercised in integration tests: " +
                    missingResponses.joinToString { it.toString() },
            )
        }

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiConstraintsCovered() {
            val missingConstraints = coverageDefinition.constraints - openApiCoverageFilter.coveredConstraints
            assertTrue(
                missingConstraints.isEmpty(),
                "The following OpenAPI validation constraints were not exercised in integration tests: " +
                    missingConstraints.joinToString { it.toString() },
            )
        }
    }
}
