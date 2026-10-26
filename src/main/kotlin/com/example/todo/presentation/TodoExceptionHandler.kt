package com.example.todo.presentation

import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.InvalidCredentialsException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.UserAlreadyExistsException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class TodoExceptionHandler {
    private val logger = LoggerFactory.getLogger(TodoExceptionHandler::class.java)

    @ExceptionHandler(
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        MissingRequestHeaderException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleBadRequest(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to "Invalid request parameters"))

    @ExceptionHandler(ActiveTodosLimitExceededException::class)
    fun handleActiveTodosLimitExceeded(
        exception: ActiveTodosLimitExceededException,
    ): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(TodoNotFoundException::class)
    fun handleTodoNotFound(exception: TodoNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflict(exception: IdempotencyConflictException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun handleUserAlreadyExists(exception: UserAlreadyExistsException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(exception: InvalidCredentialsException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(mapOf("message" to requireNotNull(exception.message)))

    @ExceptionHandler(Exception::class)
    fun handleGenericException(exception: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Unhandled server exception occurred", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("message" to "Internal server error"))
    }
}
