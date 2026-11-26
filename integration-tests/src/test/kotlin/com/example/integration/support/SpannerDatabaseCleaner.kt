package com.example.integration.support

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cleans Spanner Emulator tables across both `identity-service` and `todo-service` databases
 * via the Spanner Emulator REST API (port 9020), caching sessions across test invocations
 * to avoid per-test session lifecycle HTTP round-trips.
 */
object SpannerDatabaseCleaner {
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_CONFLICT = 409
    private const val MAX_CLEANUP_RETRIES = 5
    private const val RETRY_DELAY_MS = 100L

    private val cachedSessions = ConcurrentHashMap<String, String>()

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

    private val nonUserTodoTables = listOf(
        "todo_comments",
        "todo_subtasks",
        "todos",
        "project_members",
        "projects",
        "idempotency_records",
        "user_settings",
    )

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                cachedSessions.values.forEach { runCatching { deleteSession(it) } }
                cachedSessions.clear()
            },
        )
    }

    fun cleanAll() {
        executeCleanupStatements(
            database = ContainerTestConfig.identityDatabase,
            sqlStatements = identityTables.map { "DELETE FROM $it WHERE TRUE" },
        )
        executeCleanupStatements(
            database = ContainerTestConfig.todoDatabase,
            sqlStatements = todoTables.map { "DELETE FROM $it WHERE TRUE" },
        )
    }

    fun cleanPreservingUser(preservedUserId: UUID) {
        val userFilter = "DELETE FROM users WHERE id <> '$preservedUserId'"
        executeCleanupStatements(
            database = ContainerTestConfig.identityDatabase,
            sqlStatements = listOf("DELETE FROM user_settings WHERE TRUE", userFilter),
        )
        executeCleanupStatements(
            database = ContainerTestConfig.todoDatabase,
            sqlStatements = nonUserTodoTables.map { "DELETE FROM $it WHERE TRUE" } + userFilter,
        )
    }

    fun cleanTodosOnly() {
        executeCleanupStatements(
            database = ContainerTestConfig.todoDatabase,
            sqlStatements = listOf("todo_comments", "todo_subtasks", "todos").map { "DELETE FROM $it WHERE TRUE" },
        )
    }

    fun cleanProjectsAndTodos() {
        val tables = listOf("todo_comments", "todo_subtasks", "todos", "project_members", "projects")
        executeCleanupStatements(
            database = ContainerTestConfig.todoDatabase,
            sqlStatements = tables.map { "DELETE FROM $it WHERE TRUE" },
        )
    }

    private fun executeCleanupStatements(database: String, sqlStatements: List<String>) {
        val statementsJson = sqlStatements.joinToString(",") { """{"sql":"$it"}""" }
        for (attempt in 1..MAX_CLEANUP_RETRIES) {
            val sessionName = getOrCreateSession(database)
            val isLastAttempt = attempt == MAX_CLEANUP_RETRIES
            if (tryExecuteStatements(sessionName, statementsJson, database, isLastAttempt)) {
                break
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
    }

    private fun getOrCreateSession(database: String): String =
        cachedSessions.computeIfAbsent(database) { db ->
            val dbPath =
                "projects/${ContainerTestConfig.spannerProjectId}" +
                    "/instances/${ContainerTestConfig.spannerInstanceId}" +
                    "/databases/$db"
            createSession(dbPath)
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

    private fun beginReadWriteTransaction(sessionName: String, database: String): String? {
        val beginResponse = given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"options":{"readWrite":{}}}""")
            .`when`()
            .post("/v1/$sessionName:beginTransaction")

        if (beginResponse.statusCode() == HTTP_NOT_FOUND) {
            cachedSessions.remove(database, sessionName)
            return null
        }
        check(beginResponse.statusCode() == HTTP_OK) {
            "SpannerDatabaseCleaner beginTransaction failed with HTTP ${beginResponse.statusCode()} on $database"
        }
        return beginResponse.jsonPath().getString("id")
    }

    private fun tryExecuteStatements(
        sessionName: String,
        statements: String,
        database: String,
        isLastAttempt: Boolean,
    ): Boolean {
        val transactionId = beginReadWriteTransaction(sessionName, database) ?: return false

        val dmlStatus = given()
            .baseUri(ContainerTestConfig.spannerRestBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"transaction":{"id":"$transactionId"},"statements":[$statements],"seqno":"1"}""")
            .`when`()
            .post("/v1/$sessionName:executeBatchDml")
            .statusCode()

        var succeeded = false
        if (dmlStatus != HTTP_CONFLICT || isLastAttempt) {
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
                succeeded = true
            }
        }
        return succeeded
    }
}
