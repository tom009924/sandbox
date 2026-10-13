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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Suppress("TooManyFunctions")
class TodoApiIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
) {
    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun cleanDatabase() {
        todoRepository.findAll().forEach(todoRepository::delete)
    }

    @Test
    fun createTodoReturnsCreatedTodo() {
        validatedPostRequest()
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
    }

    @Test
    fun createAndUpdateTodoAcceptEstimatedMinutesMinimumAndRejectValuesBelowIt() {
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

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":0}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))

        validatedPostRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","estimatedMinutes":-1}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":-1}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun createAndUpdateTodoAcceptEveryPriorityValue() {
        TodoPriority.values().forEach { priority ->
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

            validatedRequest()
                .contentType(ContentType.JSON)
                .body("""{"title":"Milk bought","completed":true,"priority":"${priority.name}"}""")
                .`when`()
                .put("/api/todos/$todoId")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("priority", equalTo(priority.name))
        }
    }

    @Test
    fun createTodoWithBlankTitleReturnsBadRequest() {
        validatedPostRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun createTodoWithMissingTitleReturnsBadRequest() {
        validatedPostRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("{}")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun createTodoWithInvalidPriorityReturnsBadRequest() {
        validatedPostRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","priority":"URGENT"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
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

    @Test
    fun getMissingTodoReturnsNotFound() {
        validatedRequest()
            .`when`()
            .get("/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
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
    }

    @Test
    fun updateTodoWithBlankTitleReturnsBadRequest() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"  ","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun updateTodoWithMissingTitleReturnsBadRequest() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun updateTodoWithMissingCompletedReturnsBadRequest() {
        val todo = todoRepository.save(Todo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought"}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun updateMissingTodoReturnsNotFound() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
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

        assert(todoRepository.findById(todoId) == null)
    }

    @Test
    fun deleteMissingTodoReturnsNotFound() {
        validatedRequest()
            .`when`()
            .delete("/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
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
