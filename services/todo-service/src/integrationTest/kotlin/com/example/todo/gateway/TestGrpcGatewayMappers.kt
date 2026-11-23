package com.example.todo.gateway

import com.example.identity.grpc.v1.UserSettingsMessage
import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.grpc.v1.ProjectMemberMessage
import com.example.todo.grpc.v1.ProjectMessage
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.presentation.model.ProjectMemberResponse
import com.example.todo.presentation.model.ProjectResponse
import com.example.todo.presentation.model.TodoCommentResponse
import com.example.todo.presentation.model.TodoSubtaskResponse
import com.example.todo.presentation.model.UserSettingsResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.presentation.model.ProjectRole as ApiProjectRole
import com.example.todo.presentation.model.Todo as TodoResponse
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority

internal fun currentAuthenticatedUserId(): UUID {
    val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
    return principal.userId
}

internal fun UserSettingsMessage.toResponse() = UserSettingsResponse(
    userId = UUID.fromString(userId),
    defaultPriority = ApiTodoPriority.valueOf(defaultPriority.name),
    defaultEstimatedMinutes = if (hasDefaultEstimatedMinutes()) defaultEstimatedMinutes else null,
    maxActiveTodos = if (hasMaxActiveTodos()) maxActiveTodos else null,
)

internal fun TodoMessage.toResponse() = TodoResponse(
    id = UUID.fromString(id),
    userId = UUID.fromString(userId),
    title = title,
    completed = completed,
    description = if (hasDescription()) description else null,
    dueDate = if (hasDueDate()) LocalDate.parse(dueDate) else null,
    priority = ApiTodoPriority.valueOf(priority.name),
    estimatedMinutes = if (hasEstimatedMinutes()) estimatedMinutes else null,
    tags = tagsList,
    projectId = if (hasProjectId()) UUID.fromString(projectId) else null,
    assigneeId = if (hasAssigneeId()) UUID.fromString(assigneeId) else null,
)

internal fun TodoSubtaskMessage.toResponse() = TodoSubtaskResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    title = title,
    completed = completed,
    position = position,
)

internal fun TodoCommentMessage.toResponse() = TodoCommentResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    userId = UUID.fromString(userId),
    username = username,
    content = content,
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

internal fun ProjectMessage.toResponse() = ProjectResponse(
    id = UUID.fromString(id),
    name = name,
    description = if (hasDescription()) description else null,
    createdBy = UUID.fromString(createdBy),
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

internal fun ProjectMemberMessage.toResponse() = ProjectMemberResponse(
    projectId = UUID.fromString(projectId),
    userId = UUID.fromString(userId),
    username = username,
    role = ApiProjectRole.valueOf(role.name),
    joinedAt = Instant.parse(joinedAt).atOffset(ZoneOffset.UTC),
)
