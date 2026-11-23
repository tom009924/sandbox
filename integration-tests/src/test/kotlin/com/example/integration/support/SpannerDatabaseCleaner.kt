package com.example.integration.support

import java.sql.DriverManager

/**
 * Cleans all Spanner Emulator tables across both `identity-service` and `todo-service` databases
 * directly over JDBC so container integration tests run with clean, isolated state.
 */
object SpannerDatabaseCleaner {
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
        val jdbcUrl =
            "jdbc:cloudspanner:/projects/${ContainerTestConfig.spannerProjectId}" +
                "/instances/${ContainerTestConfig.spannerInstanceId}" +
                "/databases/$database?autoConfigEmulator=true"

        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                for (table in tables) {
                    statement.executeUpdate("DELETE FROM $table WHERE TRUE")
                }
            }
        }
    }
}
