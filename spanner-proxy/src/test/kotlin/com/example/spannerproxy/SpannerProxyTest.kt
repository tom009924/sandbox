package com.example.spannerproxy

import com.google.spanner.v1.ExecuteSqlRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpannerProxyTest {
    @Test
    fun `fails only ExecuteSql requests that insert into todos while enabled`() {
        val failure = TodoInsertFailure()
        failure.enable()

        assertTrue(shouldFailTodoInsert(EXECUTE_SQL_METHOD, insertRequest(), failure))
        assertFalse(shouldFailTodoInsert(EXECUTE_SQL_METHOD, selectRequest(), failure))
        assertFalse(shouldFailTodoInsert(OTHER_METHOD, insertRequest(), failure))
    }

    @Test
    fun `does not fail Todo inserts while disabled`() {
        val failure = TodoInsertFailure()

        assertFalse(shouldFailTodoInsert(EXECUTE_SQL_METHOD, insertRequest(), failure))
    }

    @Test
    fun `ignores malformed ExecuteSql messages`() {
        val failure = TodoInsertFailure()
        failure.enable()

        assertFalse(shouldFailTodoInsert(EXECUTE_SQL_METHOD, byteArrayOf(0x0a, 0x7f), failure))
    }

    private fun insertRequest() = ExecuteSqlRequest.newBuilder()
        .setSql("INSERT INTO todos (title) VALUES ('Buy milk')")
        .build()
        .toByteArray()

    private fun selectRequest() = ExecuteSqlRequest.newBuilder()
        .setSql("SELECT * FROM todos")
        .build()
        .toByteArray()

    private companion object {
        const val EXECUTE_SQL_METHOD = "google.spanner.v1.Spanner/ExecuteSql"
        const val OTHER_METHOD = "google.spanner.v1.Spanner/Read"
    }
}
