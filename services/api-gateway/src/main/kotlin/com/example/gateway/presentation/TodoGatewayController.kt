package com.example.gateway.presentation

import com.example.gateway.grpc.TodoGrpcClient
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.grpc.v1.UpdateTodoRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.example.todo.grpc.v1.SortOrder as ProtoSortOrder
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority
import com.example.todo.grpc.v1.TodoSortField as ProtoTodoSortField

enum class GatewayTodoSortField {
    DUE_DATE,
    PRIORITY,
    TITLE,
}

enum class GatewaySortOrder {
    ASC,
    DESC,
}

data class CreateTodoBody(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val title: String,
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    @field:Pattern(regexp = NO_CONTROL_CHARS_PATTERN)
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: GatewayTodoPriority? = null,
    @field:Min(0)
    val estimatedMinutes: Int? = null,
    @field:Size(max = MAX_TAGS_COUNT)
    val tags: List<
        @Size(max = MAX_TAG_LENGTH)
        String
        >? = null,
    val projectId: UUID? = null,
    val assigneeId: UUID? = null,
)

data class UpdateTodoBody(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val title: String,
    val completed: Boolean,
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    @field:Pattern(regexp = NO_CONTROL_CHARS_PATTERN)
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: GatewayTodoPriority? = null,
    @field:Min(0)
    val estimatedMinutes: Int? = null,
    @field:Size(max = MAX_TAGS_COUNT)
    val tags: List<
        @Size(max = MAX_TAG_LENGTH)
        String
        >? = null,
    val assigneeId: UUID? = null,
)

data class PatchTodoBody(
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val title: String? = null,
    val completed: Boolean? = null,
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    @field:Pattern(regexp = NO_CONTROL_CHARS_PATTERN)
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: GatewayTodoPriority? = null,
    @field:Min(0)
    val estimatedMinutes: Int? = null,
    @field:Size(max = MAX_TAGS_COUNT)
    val tags: List<
        @Size(max = MAX_TAG_LENGTH)
        String
        >? = null,
    val assigneeId: UUID? = null,
)

data class TodoResponse(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val completed: Boolean,
    val description: String?,
    val dueDate: LocalDate?,
    val priority: GatewayTodoPriority,
    val estimatedMinutes: Int?,
    val tags: List<String>,
    val projectId: UUID?,
    val assigneeId: UUID?,
)

data class CreateSubtaskBody(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val title: String,
)

data class PatchSubtaskBody(
    @field:Size(max = MAX_TITLE_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val title: String? = null,
    val completed: Boolean? = null,
    @field:Min(0)
    val position: Int? = null,
)

data class TodoSubtaskResponse(
    val id: UUID,
    val todoId: UUID,
    val title: String,
    val completed: Boolean,
    val position: Int,
)

data class CreateCommentBody(
    @field:NotBlank
    @field:Size(max = MAX_COMMENT_LENGTH)
    @field:Pattern(regexp = NON_WHITESPACE_PATTERN)
    val content: String,
)

data class TodoCommentResponse(
    val id: UUID,
    val todoId: UUID,
    val userId: UUID,
    val username: String,
    val content: String,
    val createdAt: OffsetDateTime,
)

data class AdminStatsResponse(
    val totalUsers: Long,
    val totalTodos: Long,
    val completedTodos: Long,
    val activeTodos: Long,
)

@RestController
@Suppress("TooManyFunctions")
class TodoGatewayController(
    private val todoGrpcClient: TodoGrpcClient,
) {
    @Suppress("LongParameterList")
    @GetMapping("/api/todos")
    fun listTodos(
        @RequestParam(required = false) completed: Boolean?,
        @RequestParam(required = false) priority: GatewayTodoPriority?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dueBefore: LocalDate?,
        @RequestParam(required = false) dueAfter: LocalDate?,
        @RequestParam(required = false) sortBy: GatewayTodoSortField?,
        @RequestParam(required = false) sortOrder: GatewaySortOrder?,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) assigneeId: UUID?,
    ): ResponseEntity<List<TodoResponse>> {
        val builder = ListTodosRequest.newBuilder().setUserId(currentUserId().toString())
        completed?.let { builder.setCompleted(it) }
        priority?.let { builder.setPriority(it.toTodoProto()) }
        tag?.let { builder.setTag(it) }
        q?.let { builder.setKeyword(it) }
        dueBefore?.let { builder.setDueBefore(it.toString()) }
        dueAfter?.let { builder.setDueAfter(it.toString()) }
        sortBy?.let { builder.setSortBy(it.toProto()) }
        sortOrder?.let { builder.setSortOrder(it.toProto()) }
        projectId?.let { builder.setProjectId(it.toString()) }
        assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.listTodos(builder.build()).map { it.toResponse() })
    }

    @GetMapping("/api/todos/{id}")
    fun getTodo(@PathVariable id: UUID): ResponseEntity<TodoResponse> {
        val todo = todoGrpcClient.getTodo(
            GetTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(todo.toResponse())
    }

    @PostMapping("/api/todos")
    fun createTodo(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: CreateTodoBody,
    ): ResponseEntity<TodoResponse> {
        if (idempotencyKey.isBlank() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be between 1 and $MAX_IDEMPOTENCY_KEY_LENGTH characters",
            )
        }
        val builder = CreateTodoRequest.newBuilder()
            .setUserId(currentUserId().toString())
            .setIdempotencyKey(idempotencyKey)
            .setTitle(body.title)
            .addAllTags(body.tags.orEmpty())
        body.description?.let { builder.setDescription(it) }
        body.dueDate?.let { builder.setDueDate(it.toString()) }
        body.priority?.let { builder.setPriority(it.toTodoProto()) }
        body.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        body.projectId?.let { builder.setProjectId(it.toString()) }
        body.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        val created = todoGrpcClient.createTodo(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @PutMapping("/api/todos/{id}")
    fun updateTodo(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateTodoBody,
    ): ResponseEntity<TodoResponse> {
        val builder = UpdateTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
            .setTitle(body.title)
            .setCompleted(body.completed)
            .addAllTags(body.tags.orEmpty())
        body.description?.let { builder.setDescription(it) }
        body.dueDate?.let { builder.setDueDate(it.toString()) }
        body.priority?.let { builder.setPriority(it.toTodoProto()) }
        body.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        body.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.updateTodo(builder.build()).toResponse())
    }

    @PatchMapping("/api/todos/{id}")
    fun patchTodo(
        @PathVariable id: UUID,
        @Valid @RequestBody body: PatchTodoBody,
    ): ResponseEntity<TodoResponse> {
        val builder = PatchTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentUserId().toString())
        body.title?.let { builder.setTitle(it) }
        body.completed?.let { builder.setCompleted(it) }
        body.description?.let { builder.setDescription(it) }
        body.dueDate?.let { builder.setDueDate(it.toString()) }
        body.priority?.let { builder.setPriority(it.toTodoProto()) }
        body.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        if (body.tags != null) {
            builder.setHasTags(true).addAllTags(body.tags)
        }
        body.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(todoGrpcClient.patchTodo(builder.build()).toResponse())
    }

    @DeleteMapping("/api/todos/{id}")
    fun deleteTodo(@PathVariable id: UUID): ResponseEntity<Unit> {
        todoGrpcClient.deleteTodo(
            DeleteTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/todos/{id}/subtasks")
    fun listSubtasks(@PathVariable id: UUID): ResponseEntity<List<TodoSubtaskResponse>> {
        val subtasks = todoGrpcClient.listSubtasks(
            ListSubtasksRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(subtasks.map { it.toResponse() })
    }

    @PostMapping("/api/todos/{id}/subtasks")
    fun createSubtask(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CreateSubtaskBody,
    ): ResponseEntity<TodoSubtaskResponse> {
        val created = todoGrpcClient.createSubtask(
            CreateSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .setTitle(body.title)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @PatchMapping("/api/todos/{id}/subtasks/{subtaskId}")
    fun patchSubtask(
        @PathVariable id: UUID,
        @PathVariable subtaskId: UUID,
        @Valid @RequestBody body: PatchSubtaskBody,
    ): ResponseEntity<TodoSubtaskResponse> {
        val builder = PatchSubtaskRequest.newBuilder()
            .setTodoId(id.toString())
            .setSubtaskId(subtaskId.toString())
            .setUserId(currentUserId().toString())
        body.title?.let { builder.setTitle(it) }
        body.completed?.let { builder.setCompleted(it) }
        body.position?.let { builder.setPosition(it) }
        return ResponseEntity.ok(todoGrpcClient.patchSubtask(builder.build()).toResponse())
    }

    @DeleteMapping("/api/todos/{id}/subtasks/{subtaskId}")
    fun deleteSubtask(
        @PathVariable id: UUID,
        @PathVariable subtaskId: UUID,
    ): ResponseEntity<Unit> {
        todoGrpcClient.deleteSubtask(
            DeleteSubtaskRequest.newBuilder()
                .setTodoId(id.toString())
                .setSubtaskId(subtaskId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/todos/{id}/comments")
    fun listComments(@PathVariable id: UUID): ResponseEntity<List<TodoCommentResponse>> {
        val comments = todoGrpcClient.listComments(
            ListCommentsRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(comments.map { it.toResponse() })
    }

    @PostMapping("/api/todos/{id}/comments")
    fun createComment(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CreateCommentBody,
    ): ResponseEntity<TodoCommentResponse> {
        val created = todoGrpcClient.createComment(
            CreateCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setUserId(currentUserId().toString())
                .setContent(body.content)
                .build(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @DeleteMapping("/api/todos/{id}/comments/{commentId}")
    fun deleteComment(
        @PathVariable id: UUID,
        @PathVariable commentId: UUID,
    ): ResponseEntity<Unit> {
        todoGrpcClient.deleteComment(
            DeleteCommentRequest.newBuilder()
                .setTodoId(id.toString())
                .setCommentId(commentId.toString())
                .setUserId(currentUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/admin/stats")
    fun getAdminStats(): ResponseEntity<AdminStatsResponse> {
        val stats = todoGrpcClient.getAdminStats()
        return ResponseEntity.ok(
            AdminStatsResponse(
                totalUsers = stats.totalUsers,
                totalTodos = stats.totalTodos,
                completedTodos = stats.completedTodos,
                activeTodos = stats.activeTodos,
            ),
        )
    }
}

private fun TodoMessage.toResponse() = TodoResponse(
    id = UUID.fromString(id),
    userId = UUID.fromString(userId),
    title = title,
    completed = completed,
    description = if (hasDescription()) description else null,
    dueDate = if (hasDueDate()) LocalDate.parse(dueDate) else null,
    priority = priority.toApi(),
    estimatedMinutes = if (hasEstimatedMinutes()) estimatedMinutes else null,
    tags = tagsList,
    projectId = if (hasProjectId()) UUID.fromString(projectId) else null,
    assigneeId = if (hasAssigneeId()) UUID.fromString(assigneeId) else null,
)

private fun TodoSubtaskMessage.toResponse() = TodoSubtaskResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    title = title,
    completed = completed,
    position = position,
)

private fun TodoCommentMessage.toResponse() = TodoCommentResponse(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    userId = UUID.fromString(userId),
    username = username,
    content = content,
    createdAt = Instant.parse(createdAt).atOffset(ZoneOffset.UTC),
)

private fun GatewayTodoPriority.toTodoProto(): ProtoTodoPriority = when (this) {
    GatewayTodoPriority.LOW -> ProtoTodoPriority.LOW
    GatewayTodoPriority.MEDIUM -> ProtoTodoPriority.MEDIUM
    GatewayTodoPriority.HIGH -> ProtoTodoPriority.HIGH
}

private fun ProtoTodoPriority.toApi(): GatewayTodoPriority = when (this) {
    ProtoTodoPriority.LOW -> GatewayTodoPriority.LOW
    ProtoTodoPriority.MEDIUM,
    ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
    ProtoTodoPriority.UNRECOGNIZED,
    -> GatewayTodoPriority.MEDIUM
    ProtoTodoPriority.HIGH -> GatewayTodoPriority.HIGH
}

private fun GatewayTodoSortField.toProto(): ProtoTodoSortField = when (this) {
    GatewayTodoSortField.DUE_DATE -> ProtoTodoSortField.DUE_DATE
    GatewayTodoSortField.PRIORITY -> ProtoTodoSortField.PRIORITY
    GatewayTodoSortField.TITLE -> ProtoTodoSortField.TITLE
}

private fun GatewaySortOrder.toProto(): ProtoSortOrder = when (this) {
    GatewaySortOrder.ASC -> ProtoSortOrder.ASC
    GatewaySortOrder.DESC -> ProtoSortOrder.DESC
}
