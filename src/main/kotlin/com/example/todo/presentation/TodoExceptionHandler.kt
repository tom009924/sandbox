package com.example.todo.presentation

import com.example.todo.domain.exception.TodoNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class TodoExceptionHandler {
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
}
