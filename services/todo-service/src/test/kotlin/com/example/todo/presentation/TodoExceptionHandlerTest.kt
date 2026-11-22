package com.example.todo.presentation

import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.InvalidCredentialsException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.UserAlreadyExistsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

class TodoExceptionHandlerTest {
    private val handler = TodoExceptionHandler()

    @Test
    fun `handleGenericException returns 500 internal server error`() {
        val response = handler.handleGenericException(RuntimeException("Database unreachable"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("Internal server error", response.body?.get("message"))
    }

    @Test
    fun `handleBadRequest returns 400 bad request`() {
        val response = handler.handleBadRequest()
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Invalid request parameters", response.body?.get("message"))
    }

    @Test
    fun `handleActiveTodosLimitExceeded returns 400 bad request`() {
        val response = handler.handleActiveTodosLimitExceeded(ActiveTodosLimitExceededException(5))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Active todos limit of 5 has been reached", response.body?.get("message"))
    }

    @Test
    fun `handleTodoNotFound returns 404 not found`() {
        val id = UUID.randomUUID()
        val response = handler.handleTodoNotFound(TodoNotFoundException(id))
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Todo $id not found", response.body?.get("message"))
    }

    @Test
    fun `handleIdempotencyConflict returns 409 conflict`() {
        val response = handler.handleIdempotencyConflict(IdempotencyConflictException("key-1"))
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Idempotency key 'key-1' was already used with a different request", response.body?.get("message"))
    }

    @Test
    fun `handleUserAlreadyExists returns 409 conflict`() {
        val response = handler.handleUserAlreadyExists(UserAlreadyExistsException("testuser"))
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("User with username 'testuser' already exists", response.body?.get("message"))
    }

    @Test
    fun `handleInvalidCredentials returns 401 unauthorized`() {
        val response = handler.handleInvalidCredentials(InvalidCredentialsException())
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("Invalid username or password", response.body?.get("message"))
    }
}
