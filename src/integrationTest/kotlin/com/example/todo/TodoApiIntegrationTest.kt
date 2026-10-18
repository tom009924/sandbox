package com.example.todo

import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.nio.file.Paths
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
        assertStoredTodo(createdId, Todo(title = "Buy milk", completed = false))

        assertPublishedEvent(TodoEventType.CREATED, createdId) {
            assertEquals("Buy milk", it.event.todo?.title)
            assertEquals(false, it.event.todo?.completed)
        }
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

        if (pubSubReceiver.isAvailable) {
            val initialEvents = pubSubReceiver.pullEvents(maxMessages = 1)
            assertEquals(1, initialEvents.size, "Expected 1 CREATED event for initial create")
            pubSubReceiver.drain()
        }

        val todoId = UUID.fromString(originalId)
        val createdTodo = requireNotNull(todoRepository.findById(todoId))
        todoRepository.save(createdTodo.copy(title = "Changed after creation"))

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
        val createdId = UUID.fromString(
            validatedPostRequest()
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
                .path<String>("id"),
        )

        assertStoredTodo(
            createdId,
            Todo(
                title = "Buy milk",
                completed = false,
                description = "Buy before the weekend",
                dueDate = LocalDate.parse("2025-04-18"),
                priority = TodoPriority.HIGH,
                estimatedMinutes = ESTIMATED_MINUTES,
            ),
        )
    }

    @Test
    fun createAndUpdateTodoAcceptEstimatedMinutesMinimum() {
        val todoId = UUID.fromString(
            validatedPostRequest()
                .contentType(ContentType.JSON)
                .body("""{"title":"Buy milk","estimatedMinutes":0}""")
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
                .extract()
                .path<String>("id"),
        )
        assertEquals(MIN_ESTIMATED_MINUTES, todoRepository.findById(todoId)?.estimatedMinutes)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":0}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))

        val stored = requireNotNull(todoRepository.findById(todoId))
        assertEquals(true, stored.completed)
        assertEquals(MIN_ESTIMATED_MINUTES, stored.estimatedMinutes)
    }

    @ParameterizedTest(name = "Priority: {0}")
    @EnumSource(TodoPriority::class)
    fun createAndUpdateTodoAcceptEveryPriorityValue(priority: TodoPriority) {
        val todoId = UUID.fromString(
            validatedPostRequest()
                .contentType(ContentType.JSON)
                .body("""{"title":"Buy milk","priority":"${priority.name}"}""")
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("priority", equalTo(priority.name))
                .extract()
                .path<String>("id"),
        )
        assertEquals(priority, todoRepository.findById(todoId)?.priority)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true,"priority":"${priority.name}"}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("priority", equalTo(priority.name))

        val stored = requireNotNull(todoRepository.findById(todoId))
        assertEquals(true, stored.completed)
        assertEquals(priority, stored.priority)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#validationErrorTestCases")
    fun validationErrorsReturnBadRequest(tc: ValidationErrorTestCase) {
        val initialCount = todoRepository.findAll().size
        val targetId = if (tc.needsExistingTodo) {
            val todo = todoRepository.save(Todo(title = "Buy milk"))
            requireNotNull(todo.id)
        } else {
            null
        }
        val expectedCount = if (tc.needsExistingTodo) initialCount + 1 else initialCount

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

        assertEquals(expectedCount, todoRepository.findAll().size, "Validation errors must not mutate database")
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
        val initialCount = todoRepository.findAll().size
        val request = validatedRequest()
        if (tc.requestBody != null) {
            request.contentType(ContentType.JSON).body(tc.requestBody)
        }

        request
            .`when`()
            .request(tc.method.name, "/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        assertEquals(
            initialCount,
            todoRepository.findAll().size,
            "Operations on missing todos must not mutate database",
        )
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

        val updatePayload = """{"title":"Milk bought","completed":true,"description":"Purchased",""" +
            """"dueDate":"2025-04-19","priority":"LOW","estimatedMinutes":45}"""

        validatedRequest()
            .contentType(ContentType.JSON)
            .body(updatePayload)
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

        assertStoredTodo(
            todoId,
            Todo(
                title = "Milk bought",
                completed = true,
                description = "Purchased",
                dueDate = LocalDate.parse("2025-04-19"),
                priority = TodoPriority.LOW,
                estimatedMinutes = UPDATED_ESTIMATED_MINUTES,
            ),
        )

        assertPublishedEvent(TodoEventType.UPDATED, todoId) {
            assertEquals("Milk bought", it.event.todo?.title)
            assertEquals(true, it.event.todo?.completed)
            assertEquals(TodoPriority.LOW, it.event.todo?.priority)
        }
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

        assertStoredTodo(todoId, Todo(title = "Milk bought", completed = true, priority = TodoPriority.MEDIUM))

        assertPublishedEvent(TodoEventType.UPDATED, todoId) {
            assertEquals("Milk bought", it.event.todo?.title)
            assertEquals(null, it.event.todo?.description)
        }
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

        assertNull(todoRepository.findById(todoId), "Deleted todo must be removed from database")

        assertPublishedEvent(TodoEventType.DELETED, todoId) {
            assertEquals(null, it.event.todo)
        }
    }

    private fun assertStoredTodo(id: UUID, expected: Todo) {
        val stored = requireNotNull(todoRepository.findById(id)) { "Todo $id must exist in database" }
        assertEquals(expected.title, stored.title)
        assertEquals(expected.completed, stored.completed)
        assertEquals(expected.priority, stored.priority)
        assertEquals(expected.description, stored.description)
        assertEquals(expected.dueDate, stored.dueDate)
        assertEquals(expected.estimatedMinutes, stored.estimatedMinutes)
    }

    private fun assertPublishedEvent(
        expectedType: TodoEventType,
        expectedTodoId: UUID,
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
        val openApiSpecUrl = Paths.get(OPEN_API_SPEC_PATH).toAbsolutePath().toUri().toString()

        val openAPI = requireNotNull(OpenAPIV3Parser().read(OPEN_API_SPEC_PATH)) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val coverageDefinition = OpenApiCoverageDefinition.from(openAPI)
        val openApiCoverageFilter = OpenApiCoverageFilter(coverageDefinition)

        val openApiValidationFilter = OpenApiValidationFilter(
            OpenApiInteractionValidator.createFor(openApiSpecUrl).build(),
        )
        val invalidRequestFilter = OpenApiValidationFilter(
            OpenApiInteractionValidator.createFor(openApiSpecUrl)
                .withWhitelist(
                    ValidationErrorsWhitelist.create().withRule(
                        "Allow invalid title pattern and missing required fields for validation error requests",
                        WhitelistRules.allOf(
                            WhitelistRules.isRequest(),
                            WhitelistRules.anyOf(
                                WhitelistRules.methodIs(PathItem.HttpMethod.POST),
                                WhitelistRules.methodIs(PathItem.HttpMethod.PUT),
                            ),
                            WhitelistRules.pathContainsSubstring("/api/todos"),
                            WhitelistRules.anyOf(
                                WhitelistRules.messageHasKey("validation.request.body.schema.pattern"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.required"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.enum"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.minimum"),
                            ),
                        ),
                    ),
                )
                .build(),
        )

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
