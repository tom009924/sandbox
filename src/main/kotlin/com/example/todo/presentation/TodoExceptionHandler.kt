package com.example.todo.presentation

import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.TodoNotFoundException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class TodoExceptionHandler {
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to "Invalid request parameters"))

    @ExceptionHandler(TodoNotFoundException::class)
    fun handleTodoNotFound(exception: TodoNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(DataAccessException::class)
    fun handleDatabaseUnavailable(exception: DataAccessException): ResponseEntity<Map<String, String>> {
        logger.warn("Spanner database operation failed", exception)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("message" to "The database is temporarily unavailable"))
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TodoExceptionHandler::class.java)
    }

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflict(exception: IdempotencyConflictException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("message" to requireNotNull(exception.message)))
}
