package com.example.todo.infrastructure.grpc

import com.example.todo.domain.exception.ActiveTodosLimitExceededException
import com.example.todo.domain.exception.IdempotencyConflictException
import com.example.todo.domain.exception.InvalidCredentialsException
import com.example.todo.domain.exception.InvalidTodoAssigneeException
import com.example.todo.domain.exception.LastProjectOwnerException
import com.example.todo.domain.exception.ProjectForbiddenException
import com.example.todo.domain.exception.ProjectMemberAlreadyExistsException
import com.example.todo.domain.exception.ProjectMemberNotFoundException
import com.example.todo.domain.exception.ProjectNotFoundException
import com.example.todo.domain.exception.TodoCommentForbiddenException
import com.example.todo.domain.exception.TodoCommentNotFoundException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.TodoSubtaskNotFoundException
import com.example.todo.domain.exception.UserAlreadyExistsException
import com.example.todo.domain.exception.UserNotFoundException
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.example.todo.infrastructure.grpc.TodoGrpcRpcExecutor")

@Suppress("TooGenericExceptionCaught")
internal fun <T> executeRpc(
    responseObserver: StreamObserver<T>,
    block: () -> T,
) {
    try {
        responseObserver.onNext(block())
        responseObserver.onCompleted()
    } catch (ex: RuntimeException) {
        val status = mapExceptionToStatus(ex)
        if (status != null) {
            responseObserver.onError(status.withDescription(ex.message).asRuntimeException())
        } else {
            logger.error("Unexpected runtime error while executing todo gRPC call", ex)
            responseObserver.onError(Status.INTERNAL.withDescription("Internal server error").asRuntimeException())
        }
    } catch (ex: Exception) {
        logger.error("Unexpected error while executing todo gRPC call", ex)
        responseObserver.onError(Status.INTERNAL.withDescription("Internal server error").asRuntimeException())
    }
}

private fun mapExceptionToStatus(ex: RuntimeException): Status? = when (ex) {
    is ActiveTodosLimitExceededException,
    is InvalidTodoAssigneeException,
    is IllegalArgumentException,
    is DateTimeParseException,
    -> Status.INVALID_ARGUMENT

    is InvalidCredentialsException -> Status.UNAUTHENTICATED

    is ProjectForbiddenException,
    is TodoCommentForbiddenException,
    -> Status.PERMISSION_DENIED

    is TodoNotFoundException,
    is ProjectNotFoundException,
    is ProjectMemberNotFoundException,
    is TodoSubtaskNotFoundException,
    is TodoCommentNotFoundException,
    is UserNotFoundException,
    -> Status.NOT_FOUND

    is IdempotencyConflictException,
    is ProjectMemberAlreadyExistsException,
    is LastProjectOwnerException,
    is UserAlreadyExistsException,
    -> Status.ALREADY_EXISTS

    else -> null
}

internal fun parseUuid(raw: String, fieldName: String): UUID = try {
    UUID.fromString(raw)
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid UUID for '$fieldName': $raw", ex)
}

internal fun parseLocalDate(raw: String, fieldName: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (ex: DateTimeParseException) {
    throw IllegalArgumentException("Invalid date for '$fieldName': $raw", ex)
}
