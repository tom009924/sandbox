package com.example.todo

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
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
    private val openApiValidationFilter = OpenApiValidationFilter(
        Paths.get("docs/openapi.yaml").toAbsolutePath().toUri().toString(),
    )

    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun cleanDatabase() {
        todoRepository.deleteAll()
    }

    @Test
    fun createListUpdateAndDeleteTodoOverHttp() {
        val id = given()
            .port(port)
            .filter(openApiValidationFilter)
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
            .extract()
            .path<Int>("id")
            .toLong()

        given()
            .port(port)
            .filter(openApiValidationFilter)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", hasItem(id.toInt()))

        given()
            .port(port)
            .filter(openApiValidationFilter)
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$id")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))

        given()
            .port(port)
            .filter(openApiValidationFilter)
            .`when`()
            .delete("/api/todos/$id")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        given()
            .port(port)
            .filter(openApiValidationFilter)
            .`when`()
            .get("/api/todos/$id")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun rejectBlankTodoTitleOverHttp() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .body("""{"title":"  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }
}
