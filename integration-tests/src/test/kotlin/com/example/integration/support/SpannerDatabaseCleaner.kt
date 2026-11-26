package com.example.integration.support

import io.restassured.RestAssured.given
import io.restassured.http.ContentType

/**
 * Cleans all Spanner Emulator tables across both `identity-service` and `todo-service` databases
 * via the Spanner Emulator REST API (port 9020), avoiding heavy JDBC driver dependencies.
 */
object SpannerDatabaseCleaner {
    private const val HTTP_OK = 200
    private const val HTTP_CONFLICT = 409
    private const val MAX_CLEANUP_RETRIES = 5
    private const val RETRY_DELAY_MS = 100L

    private val identityTables = listOf(
        "user_settings",
        "users",
    )

    private val todoTables = listOf(
        "todo_comments",
        "todo_subtasks",
        "todos",
        "project_members",
        "projects",
        "idempotency_records",
        "user_settings",
        "users",
    )

    fun cleanAll() {
        cleanDatabase(ContainerTestConfig.identityDatabase, identityTables)
        cleanDatabase(ContainerTestConfig.todoDatabase, todoTables)
    }

    fun cleanTodosOnly() {
        cleanDatabase(ContainerTestConfig.todoDatabase, listOf("todo_comments", "todo_subtasks", "todos"))
    }

    private fun cleanDatabase(database: String, tables: List<String>) {
        val dbPath =
            "projects/${ContainerTestConfig.spannerProjectId}" +
                "/instances/${ContainerTestConfig.spannerInstanceId}" +
                "/databases/$database"

        val sessionName = createSession(dbPath)
        try {
            val statements = tables.joinToString(",") { """{"sql":"DELETE FROM $it WHERE TRUE"}""" }
            for (attempt in 1..MAX_CLEANUP_RETRIES) {
                val isLastAttempt = attempt == MAX_CLEANUP_RETRIES
                if (tryDeleteTables(sessionName, statements, database, isLastAttempt)) {
                    break
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        } finally {
            deleteSession(sessionName)
        }
    }

    private fun createSession(dbPath: String): String = given()
        .baseUri(ContainerTestConfig.spannerRestBaseUrl)
        .contentType(ContentType.JSON)
        .body("{}")
        .`when`()
        .post("/v1/$dbPath/sessions")
        .then()
        .statusCode(HTTP_OK)
        .extract()
        .path("name")

    private fun deleteSession(sessionName: String) {
        given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .`when`()
            .delete("/v1/$sessionName")
    }

    private fun tryDeleteTables(
        sessionName: String,
        statements: String,
        database: String,
        isLastAttempt: Boolean,
    ): Boolean {
        val transactionId = given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"options":{"readWrite":{}}}""")
            .`when`()
            .post("/v1/$sessionName:beginTransaction")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path<String>("id")

        val dmlStatus = given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"transaction":{"id":"$transactionId"},"statements":[$statements],"seqno":"1"}""")
            .`when`()
            .post("/v1/$sessionName:executeBatchDml")
            .statusCode()

        if (dmlStatus == HTTP_CONFLICT && !isLastAttempt) {
            return false
        }
        check(dmlStatus == HTTP_OK) {
            "SpannerDatabaseCleaner executeBatchDml failed with HTTP $dmlStatus on $database"
        }

        val commitStatus = given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"transactionId":"$transactionId"}""")
            .`when`()
            .post("/v1/$sessionName:commit")
            .statusCode()

        val retryCommit = commitStatus == HTTP_CONFLICT && !isLastAttempt
        if (!retryCommit) {
            check(commitStatus == HTTP_OK) {
                "SpannerDatabaseCleaner commit failed with HTTP $commitStatus on $database"
            }
        }
        return !retryCommit
    }
}
