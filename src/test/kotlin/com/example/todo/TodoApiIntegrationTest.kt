package com.example.todo

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

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
    fun `create list update and delete todo over HTTP`() {
        val id = given()
            .port(port)
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(201)
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
            .extract()
            .path<Int>("id")
            .toLong()

        given()
            .port(port)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(200)
            .body("id", hasItem(id.toInt()))

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$id")
            .then()
            .statusCode(200)
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))

        given()
            .port(port)
            .`when`()
            .delete("/api/todos/$id")
            .then()
            .statusCode(204)

        given()
            .port(port)
            .`when`()
            .get("/api/todos/$id")
            .then()
            .statusCode(404)
    }

    @Test
    fun `reject blank todo title over HTTP`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .body("""{"title":"  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(400)
    }
}
