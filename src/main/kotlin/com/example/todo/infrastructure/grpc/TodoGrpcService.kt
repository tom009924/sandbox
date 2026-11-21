package com.example.todo.infrastructure.grpc

import com.example.todo.application.AdminStatsService
import com.example.todo.application.PatchTodoCommand
import com.example.todo.application.PatchTodoSubtaskCommand
import com.example.todo.application.SortOrder
import com.example.todo.application.TodoCommand
import com.example.todo.application.TodoCommentInfo
import com.example.todo.application.TodoCommentService
import com.example.todo.application.TodoListQuery
import com.example.todo.application.TodoService
import com.example.todo.application.TodoSortField
import com.example.todo.application.TodoSubtaskService
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.grpc.v1.AdminStatsMessage
import com.example.todo.grpc.v1.CreateCommentRequest
import com.example.todo.grpc.v1.CreateSubtaskRequest
import com.example.todo.grpc.v1.CreateTodoRequest
import com.example.todo.grpc.v1.DeleteCommentRequest
import com.example.todo.grpc.v1.DeleteSubtaskRequest
import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.EmptyResponse
import com.example.todo.grpc.v1.GetAdminStatsRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListCommentsRequest
import com.example.todo.grpc.v1.ListCommentsResponse
import com.example.todo.grpc.v1.ListSubtasksRequest
import com.example.todo.grpc.v1.ListSubtasksResponse
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.grpc.v1.ListTodosResponse
import com.example.todo.grpc.v1.PatchSubtaskRequest
import com.example.todo.grpc.v1.PatchTodoRequest
import com.example.todo.grpc.v1.TodoCommentMessage
import com.example.todo.grpc.v1.TodoMessage
import com.example.todo.grpc.v1.TodoServiceGrpc
import com.example.todo.grpc.v1.TodoSubtaskMessage
import com.example.todo.grpc.v1.UpdateTodoRequest
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import com.example.todo.grpc.v1.SortOrder as ProtoSortOrder
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority
import com.example.todo.grpc.v1.TodoSortField as ProtoTodoSortField

@Component
@Suppress("TooManyFunctions")
class TodoGrpcService(
    private val todoService: TodoService,
    private val todoSubtaskService: TodoSubtaskService,
    private val todoCommentService: TodoCommentService,
    private val adminStatsService: AdminStatsService,
) : TodoServiceGrpc.TodoServiceImplBase() {

    @Transactional(readOnly = true)
    override fun listTodos(
        request: ListTodosRequest,
        responseObserver: StreamObserver<ListTodosResponse>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            val query = TodoListQuery(
                completed = if (request.hasCompleted()) request.completed else null,
                priority = request.priority.toDomainOrNull(),
                tag = if (request.hasTag()) request.tag else null,
                keyword = if (request.hasKeyword()) request.keyword else null,
                dueBefore = if (request.hasDueBefore()) parseLocalDate(request.dueBefore, "due_before") else null,
                dueAfter = if (request.hasDueAfter()) parseLocalDate(request.dueAfter, "due_after") else null,
                sortBy = request.sortBy.toDomainOrNull(),
                sortOrder = request.sortOrder.toDomain(),
                projectId = if (request.hasProjectId()) parseUuid(request.projectId, "project_id") else null,
                assigneeId = if (request.hasAssigneeId()) parseUuid(request.assigneeId, "assignee_id") else null,
            )
            val todos = todoService.list(userId, query).map { it.toProto() }
            ListTodosResponse.newBuilder().addAllTodos(todos).build()
        }
    }

    @Transactional(readOnly = true)
    override fun getTodo(
        request: GetTodoRequest,
        responseObserver: StreamObserver<TodoMessage>,
    ) {
        executeRpc(responseObserver) {
            val id = parseUuid(request.id, "id")
            val userId = parseUuid(request.userId, "user_id")
            todoService.get(id, userId).toProto()
        }
    }

    @Transactional
    override fun createTodo(
        request: CreateTodoRequest,
        responseObserver: StreamObserver<TodoMessage>,
    ) {
        executeRpc(responseObserver) {
            val userId = parseUuid(request.userId, "user_id")
            val command = TodoCommand(
                title = request.title,
                description = if (request.hasDescription()) request.description else null,
                dueDate = if (request.hasDueDate()) parseLocalDate(request.dueDate, "due_date") else null,
                priority = request.priority.toDomainOrNull(),
                estimatedMinutes = if (request.hasEstimatedMinutes()) request.estimatedMinutes else null,
                tags = request.tagsList,
                projectId = if (request.hasProjectId()) parseUuid(request.projectId, "project_id") else null,
                assigneeId = if (request.hasAssigneeId()) parseUuid(request.assigneeId, "assignee_id") else null,
            )
            todoService.createIdempotent(userId, request.idempotencyKey, command).toProto()
        }
    }

    @Transactional
    override fun updateTodo(
        request: UpdateTodoRequest,
        responseObserver: StreamObserver<TodoMessage>,
    ) {
        executeRpc(responseObserver) {
            val id = parseUuid(request.id, "id")
            val userId = parseUuid(request.userId, "user_id")
            val command = TodoCommand(
                title = request.title,
                completed = request.completed,
                description = if (request.hasDescription()) request.description else null,
                dueDate = if (request.hasDueDate()) parseLocalDate(request.dueDate, "due_date") else null,
                priority = request.priority.toDomainOrNull() ?: TodoPriority.MEDIUM,
                estimatedMinutes = if (request.hasEstimatedMinutes()) request.estimatedMinutes else null,
                tags = request.tagsList,
                assigneeId = if (request.hasAssigneeId()) parseUuid(request.assigneeId, "assignee_id") else null,
            )
            todoService.update(id, userId, command).toProto()
        }
    }

    @Transactional
    override fun patchTodo(
        request: PatchTodoRequest,
        responseObserver: StreamObserver<TodoMessage>,
    ) {
        executeRpc(responseObserver) {
            val id = parseUuid(request.id, "id")
            val userId = parseUuid(request.userId, "user_id")
            val command = PatchTodoCommand(
                title = if (request.hasTitle()) request.title else null,
                completed = if (request.hasCompleted()) request.completed else null,
                description = if (request.hasDescription()) request.description else null,
                dueDate = if (request.hasDueDate()) parseLocalDate(request.dueDate, "due_date") else null,
                priority = request.priority.toDomainOrNull(),
                estimatedMinutes = if (request.hasEstimatedMinutes()) request.estimatedMinutes else null,
                tags = if (request.hasTags) request.tagsList else null,
                assigneeId = if (request.hasAssigneeId()) parseUuid(request.assigneeId, "assignee_id") else null,
            )
            todoService.patch(id, userId, command).toProto()
        }
    }

    @Transactional
    override fun deleteTodo(
        request: DeleteTodoRequest,
        responseObserver: StreamObserver<EmptyResponse>,
    ) {
        executeRpc(responseObserver) {
            val id = parseUuid(request.id, "id")
            val userId = parseUuid(request.userId, "user_id")
            todoService.delete(id, userId)
            EmptyResponse.getDefaultInstance()
        }
    }

    @Transactional(readOnly = true)
    override fun listSubtasks(
        request: ListSubtasksRequest,
        responseObserver: StreamObserver<ListSubtasksResponse>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val userId = parseUuid(request.userId, "user_id")
            val subtasks = todoSubtaskService.list(todoId, userId).map { it.toProto() }
            ListSubtasksResponse.newBuilder().addAllSubtasks(subtasks).build()
        }
    }

    @Transactional
    override fun createSubtask(
        request: CreateSubtaskRequest,
        responseObserver: StreamObserver<TodoSubtaskMessage>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val userId = parseUuid(request.userId, "user_id")
            todoSubtaskService.add(todoId, userId, request.title).toProto()
        }
    }

    @Transactional
    override fun patchSubtask(
        request: PatchSubtaskRequest,
        responseObserver: StreamObserver<TodoSubtaskMessage>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val subtaskId = parseUuid(request.subtaskId, "subtask_id")
            val userId = parseUuid(request.userId, "user_id")
            val command = PatchTodoSubtaskCommand(
                title = if (request.hasTitle()) request.title else null,
                completed = if (request.hasCompleted()) request.completed else null,
                position = if (request.hasPosition()) request.position else null,
            )
            todoSubtaskService.patch(todoId, subtaskId, userId, command).toProto()
        }
    }

    @Transactional
    override fun deleteSubtask(
        request: DeleteSubtaskRequest,
        responseObserver: StreamObserver<EmptyResponse>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val subtaskId = parseUuid(request.subtaskId, "subtask_id")
            val userId = parseUuid(request.userId, "user_id")
            todoSubtaskService.delete(todoId, subtaskId, userId)
            EmptyResponse.getDefaultInstance()
        }
    }

    @Transactional(readOnly = true)
    override fun listComments(
        request: ListCommentsRequest,
        responseObserver: StreamObserver<ListCommentsResponse>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val userId = parseUuid(request.userId, "user_id")
            val comments = todoCommentService.list(todoId, userId).map { it.toProto() }
            ListCommentsResponse.newBuilder().addAllComments(comments).build()
        }
    }

    @Transactional
    override fun createComment(
        request: CreateCommentRequest,
        responseObserver: StreamObserver<TodoCommentMessage>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val userId = parseUuid(request.userId, "user_id")
            todoCommentService.add(todoId, userId, request.content).toProto()
        }
    }

    @Transactional
    override fun deleteComment(
        request: DeleteCommentRequest,
        responseObserver: StreamObserver<EmptyResponse>,
    ) {
        executeRpc(responseObserver) {
            val todoId = parseUuid(request.todoId, "todo_id")
            val commentId = parseUuid(request.commentId, "comment_id")
            val userId = parseUuid(request.userId, "user_id")
            todoCommentService.delete(todoId, commentId, userId)
            EmptyResponse.getDefaultInstance()
        }
    }

    @Transactional(readOnly = true)
    override fun getAdminStats(
        request: GetAdminStatsRequest,
        responseObserver: StreamObserver<AdminStatsMessage>,
    ) {
        executeRpc(responseObserver) {
            val stats = adminStatsService.getStats()
            AdminStatsMessage.newBuilder()
                .setTotalUsers(stats.totalUsers)
                .setTotalTodos(stats.totalTodos)
                .setCompletedTodos(stats.completedTodos)
                .setActiveTodos(stats.activeTodos)
                .build()
        }
    }
}

private fun Todo.toProto(): TodoMessage {
    val builder = TodoMessage.newBuilder()
        .setId(requireNotNull(id) { "Todo id must not be null" }.toString())
        .setUserId(userId.toString())
        .setTitle(title)
        .setCompleted(completed)
        .setPriority(priority.toProto())
        .addAllTags(tags)
    description?.let { builder.setDescription(it) }
    dueDate?.let { builder.setDueDate(it.toString()) }
    estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
    projectId?.let { builder.setProjectId(it.toString()) }
    assigneeId?.let { builder.setAssigneeId(it.toString()) }
    return builder.build()
}

private fun TodoSubtask.toProto(): TodoSubtaskMessage = TodoSubtaskMessage.newBuilder()
    .setId(requireNotNull(id) { "TodoSubtask id must not be null" }.toString())
    .setTodoId(todoId.toString())
    .setTitle(title)
    .setCompleted(completed)
    .setPosition(position)
    .build()

private fun TodoCommentInfo.toProto(): TodoCommentMessage = TodoCommentMessage.newBuilder()
    .setId(id.toString())
    .setTodoId(todoId.toString())
    .setUserId(userId.toString())
    .setUsername(username)
    .setContent(content)
    .setCreatedAt(createdAt.toString())
    .build()

private fun TodoPriority.toProto(): ProtoTodoPriority = when (this) {
    TodoPriority.LOW -> ProtoTodoPriority.LOW
    TodoPriority.MEDIUM -> ProtoTodoPriority.MEDIUM
    TodoPriority.HIGH -> ProtoTodoPriority.HIGH
}

private fun ProtoTodoPriority.toDomainOrNull(): TodoPriority? = when (this) {
    ProtoTodoPriority.LOW -> TodoPriority.LOW
    ProtoTodoPriority.MEDIUM -> TodoPriority.MEDIUM
    ProtoTodoPriority.HIGH -> TodoPriority.HIGH
    ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
    ProtoTodoPriority.UNRECOGNIZED,
    -> null
}

private fun ProtoTodoSortField.toDomainOrNull(): TodoSortField? = when (this) {
    ProtoTodoSortField.DUE_DATE -> TodoSortField.DUE_DATE
    ProtoTodoSortField.PRIORITY -> TodoSortField.PRIORITY
    ProtoTodoSortField.TITLE -> TodoSortField.TITLE
    ProtoTodoSortField.TODO_SORT_FIELD_UNSPECIFIED,
    ProtoTodoSortField.UNRECOGNIZED,
    -> null
}

private fun ProtoSortOrder.toDomain(): SortOrder = when (this) {
    ProtoSortOrder.DESC -> SortOrder.DESC
    ProtoSortOrder.ASC,
    ProtoSortOrder.SORT_ORDER_UNSPECIFIED,
    ProtoSortOrder.UNRECOGNIZED,
    -> SortOrder.ASC
}
