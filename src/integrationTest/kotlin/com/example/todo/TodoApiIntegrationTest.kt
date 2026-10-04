package com.example.todo

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import java.nio.file.Paths
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
        todoRepository.deleteAll()
    }

    @Test
    fun createTodoReturnsCreatedTodo() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
    }

    @Test
    fun createTodoWithBlankTitleReturnsBadRequest() {
        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("""{"title":"  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun createTodoWithMissingTitleReturnsBadRequest() {
        validatedRequest(invalidRequest = true)
            .contentType(ContentType.JSON)
            .body("{}")
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
        val todo = todoRepository.save(Todo(title = "Buy milk", completed = true))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", equalTo(todoId.toString()))
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
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
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))
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

        assert(!todoRepository.existsById(todoId))
    }

    @Test
    fun deleteMissingTodoReturnsNotFound() {
        validatedRequest()
            .`when`()
            .delete("/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    private fun validatedRequest(invalidRequest: Boolean = false) =
        given()
            .port(port)
            .filter(openApiCoverageFilter)
            .filter(if (invalidRequest) invalidRequestFilter else openApiValidationFilter)

    private companion object {
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val OPEN_API_SPEC_PATH = "docs/openapi.yaml"
        val openApiSpecUrl = Paths.get(OPEN_API_SPEC_PATH).toAbsolutePath().toUri().toString()

        val openAPI = requireNotNull(OpenAPIV3Parser().read(OPEN_API_SPEC_PATH)) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val expectedResponses: Set<OpenApiResponse> = openAPI.paths.flatMap { (path, pathItem) ->
            pathItem.readOperationsMap().flatMap { (method, operation) ->
                operation.responses.keys.mapNotNull { it.toIntOrNull() }.map { statusCode ->
                    OpenApiResponse(method, path, statusCode)
                }
            }
        }.toSet()

        val expectedConstraints: Set<OpenApiConstraint> = run {
            val schemas = openAPI.components?.schemas.orEmpty()
            openAPI.paths.flatMap { (path, pathItem) ->
                pathItem.readOperationsMap().flatMap { (method, operation) ->
                    val schemaRef = operation.requestBody?.content?.get("application/json")?.schema?.`$ref`
                    val schemaName = schemaRef?.substringAfterLast("/")
                    val schema = schemas[schemaName]

                    val constraints = mutableListOf<OpenApiConstraint>()
                    if (schema != null) {
                        schema.required?.forEach { requiredProp ->
                            constraints.add(
                                OpenApiConstraint(
                                    method,
                                    path,
                                    requiredProp,
                                    OpenApiConstraintType.REQUIRED,
                                ),
                            )
                        }
                        schema.properties?.forEach { (propName, propSchema) ->
                            propSchema.pattern?.let { pattern ->
                                constraints.add(
                                    OpenApiConstraint(
                                        method,
                                        path,
                                        propName,
                                        OpenApiConstraintType.PATTERN,
                                        pattern,
                                    ),
                                )
                            }
                        }
                    }
                    constraints
                }
            }.toSet()
        }

        val openApiCoverageFilter = OpenApiCoverageFilter(expectedResponses, expectedConstraints)

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
                            ),
                        ),
                    ),
                )
                .build(),
        )

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiResponsesCovered() {
            val missingResponses = expectedResponses - openApiCoverageFilter.coveredResponses
            assertTrue(
                missingResponses.isEmpty(),
                "The following OpenAPI responses were not exercised in integration tests: " +
                    missingResponses.joinToString { it.toString() },
            )
        }

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiConstraintsCovered() {
            val missingConstraints = expectedConstraints - openApiCoverageFilter.coveredConstraints
            assertTrue(
                missingConstraints.isEmpty(),
                "The following OpenAPI validation constraints were not exercised in integration tests: " +
                    missingConstraints.joinToString { it.toString() },
            )
        }
    }
}
