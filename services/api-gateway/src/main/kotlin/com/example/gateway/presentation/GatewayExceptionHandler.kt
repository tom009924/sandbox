package com.example.gateway.presentation

import io.grpc.Status
import io.grpc.StatusRuntimeException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GatewayExceptionHandler {
    private val logger = LoggerFactory.getLogger(GatewayExceptionHandler::class.java)

    @ExceptionHandler(StatusRuntimeException::class)
    fun handleGrpcException(ex: StatusRuntimeException): ResponseEntity<Map<String, String>> {
        val httpStatus = mapGrpcStatus(ex.status.code)
        val message = if (httpStatus.is5xxServerError) {
            logger.error("Downstream gRPC call failed with status {}", ex.status, ex)
            httpStatus.reasonPhrase
        } else {
            ex.status.description ?: httpStatus.reasonPhrase
        }
        return ResponseEntity.status(httpStatus).body(mapOf("message" to message))
    }

    @ExceptionHandler(
        ConstraintViolationException::class,
        HandlerMethodValidationException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        ServletRequestBindingException::class,
        HttpMessageNotReadableException::class,
        InvalidMediaTypeException::class,
        MultipartException::class,
    )
    fun handleBadRequest(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to "Invalid request parameters"))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(mapOf("message" to "Unsupported media type"))

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .body(mapOf("message" to "Not acceptable"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(mapOf("message" to "Method not allowed"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("message" to "Not found"))

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode)
            .body(mapOf("message" to (ex.reason ?: "Request failed")))

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Unhandled gateway exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("message" to "Internal server error"))
    }

    private fun mapGrpcStatus(code: Status.Code): HttpStatus = when (code) {
        Status.Code.INVALID_ARGUMENT,
        Status.Code.FAILED_PRECONDITION,
        -> HttpStatus.BAD_REQUEST
        Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
        Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
        Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
        Status.Code.ALREADY_EXISTS -> HttpStatus.CONFLICT
        Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
        Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }
}
