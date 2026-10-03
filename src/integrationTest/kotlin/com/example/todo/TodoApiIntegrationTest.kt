package com.example.todo

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.oas.models.PathItem
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import java.nio.file.Paths

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
            .body("id", hasItem(requireNotNull(todo.id).toInt()))
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
            .body("id", equalTo(todoId.toInt()))
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
            .filter(if (invalidRequest) invalidRequestFilter else openApiValidationFilter)

    private companion object {
        const val MISSING_TODO_ID = 999_999L
        val openApiSpecUrl = Paths.get("docs/openapi.yaml").toAbsolutePath().toUri().toString()
        val openApiValidationFilter = OpenApiValidationFilter(
            OpenApiInteractionValidator.createFor(openApiSpecUrl).build(),
        )
        val invalidRequestFilter = OpenApiValidationFilter(
            OpenApiInteractionValidator.createFor(openApiSpecUrl)
                .withWhitelist(
                    ValidationErrorsWhitelist.create().withRule(
                        "Allow invalid title pattern only for validation error requests",
                        WhitelistRules.allOf(
                            WhitelistRules.isRequest(),
                            WhitelistRules.anyOf(
                                WhitelistRules.methodIs(PathItem.HttpMethod.POST),
                                WhitelistRules.methodIs(PathItem.HttpMethod.PUT),
                            ),
                            WhitelistRules.pathContainsSubstring("/api/todos"),
                            WhitelistRules.messageHasKey("validation.request.body.schema.pattern"),
                        ),
                    ),
                )
                .build(),
        )
    }
}
