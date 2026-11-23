package com.example.todo.gateway

import io.grpc.Status
import io.grpc.StatusRuntimeException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class TestGrpcGatewayExceptionHandler {
    private val logger = LoggerFactory.getLogger(TestGrpcGatewayExceptionHandler::class.java)

    @ExceptionHandler(StatusRuntimeException::class)
    fun handleGrpcException(ex: StatusRuntimeException): ResponseEntity<Map<String, String>> {
        val httpStatus = when (ex.status.code) {
            Status.Code.INVALID_ARGUMENT,
            Status.Code.FAILED_PRECONDITION,
            -> HttpStatus.BAD_REQUEST
            Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.ALREADY_EXISTS -> HttpStatus.CONFLICT
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val message = ex.status.description ?: httpStatus.reasonPhrase
        return ResponseEntity.status(httpStatus).body(mapOf("message" to message))
    }

    @ExceptionHandler(
        ConstraintViolationException::class,
        HandlerMethodValidationException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        MissingRequestHeaderException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleBadRequest(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to "Invalid request parameters"))

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode)
            .body(mapOf("message" to (ex.reason ?: "Request failed")))

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Unhandled test gateway exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("message" to "Internal server error"))
    }
}
