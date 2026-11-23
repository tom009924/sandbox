package com.example.todo.gateway

import com.example.todo.grpc.v1.DeleteTodoRequest
import com.example.todo.grpc.v1.GetTodoRequest
import com.example.todo.grpc.v1.ListTodosRequest
import com.example.todo.presentation.api.TodosApi
import com.example.todo.presentation.model.CreateTodoRequest
import com.example.todo.presentation.model.PatchTodoRequest
import com.example.todo.presentation.model.UpdateTodoRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID
import com.example.todo.grpc.v1.CreateTodoRequest as ProtoCreateTodoRequest
import com.example.todo.grpc.v1.PatchTodoRequest as ProtoPatchTodoRequest
import com.example.todo.grpc.v1.SortOrder as ProtoSortOrder
import com.example.todo.grpc.v1.TodoPriority as ProtoTodoPriority
import com.example.todo.grpc.v1.TodoSortField as ProtoTodoSortField
import com.example.todo.grpc.v1.UpdateTodoRequest as ProtoUpdateTodoRequest
import com.example.todo.presentation.model.SortOrder as ApiSortOrder
import com.example.todo.presentation.model.Todo as TodoResponse
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority
import com.example.todo.presentation.model.TodoSortField as ApiTodoSortField

@RestController
class TestTodoGatewayController(
    private val bridge: InProcessTestGrpcBridge,
) : TodosApi {

    @Suppress("LongParameterList")
    override fun listTodos(
        completed: Boolean?,
        priority: ApiTodoPriority?,
        tag: String?,
        q: String?,
        dueBefore: LocalDate?,
        dueAfter: LocalDate?,
        sortBy: ApiTodoSortField?,
        sortOrder: ApiSortOrder?,
        projectId: UUID?,
        assigneeId: UUID?,
    ): ResponseEntity<List<TodoResponse>> {
        val builder = ListTodosRequest.newBuilder().setUserId(currentAuthenticatedUserId().toString())
        completed?.let { builder.setCompleted(it) }
        priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        tag?.let { builder.setTag(it) }
        q?.let { builder.setKeyword(it) }
        dueBefore?.let { builder.setDueBefore(it.toString()) }
        dueAfter?.let { builder.setDueAfter(it.toString()) }
        sortBy?.let { builder.setSortBy(ProtoTodoSortField.valueOf(it.name)) }
        sortOrder?.let { builder.setSortOrder(ProtoSortOrder.valueOf(it.name)) }
        projectId?.let { builder.setProjectId(it.toString()) }
        assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.listTodos(builder.build()).todosList.map { it.toResponse() })
    }

    override fun getTodo(id: UUID): ResponseEntity<TodoResponse> {
        val todo = bridge.todoStub.getTodo(
            GetTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        )
        return ResponseEntity.ok(todo.toResponse())
    }

    override fun createTodo(
        idempotencyKey: String,
        createTodoRequest: CreateTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoCreateTodoRequest.newBuilder()
            .setUserId(currentAuthenticatedUserId().toString())
            .setIdempotencyKey(idempotencyKey)
            .setTitle(createTodoRequest.title)
            .addAllTags(createTodoRequest.tags.orEmpty())
        createTodoRequest.description?.let { builder.setDescription(it) }
        createTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        createTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        createTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        createTodoRequest.projectId?.let { builder.setProjectId(it.toString()) }
        createTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        val created = bridge.todoStub.createTodo(builder.build())
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    override fun updateTodo(
        id: UUID,
        updateTodoRequest: UpdateTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoUpdateTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentAuthenticatedUserId().toString())
            .setTitle(updateTodoRequest.title)
            .setCompleted(updateTodoRequest.completed)
            .addAllTags(updateTodoRequest.tags.orEmpty())
        updateTodoRequest.description?.let { builder.setDescription(it) }
        updateTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        updateTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        updateTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        updateTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.updateTodo(builder.build()).toResponse())
    }

    override fun patchTodo(
        id: UUID,
        patchTodoRequest: PatchTodoRequest,
    ): ResponseEntity<TodoResponse> {
        val builder = ProtoPatchTodoRequest.newBuilder()
            .setId(id.toString())
            .setUserId(currentAuthenticatedUserId().toString())
        patchTodoRequest.title?.let { builder.setTitle(it) }
        patchTodoRequest.completed?.let { builder.setCompleted(it) }
        patchTodoRequest.description?.let { builder.setDescription(it) }
        patchTodoRequest.dueDate?.let { builder.setDueDate(it.toString()) }
        patchTodoRequest.priority?.let { builder.setPriority(ProtoTodoPriority.valueOf(it.name)) }
        patchTodoRequest.estimatedMinutes?.let { builder.setEstimatedMinutes(it) }
        if (patchTodoRequest.tags != null) {
            builder.setHasTags(true).addAllTags(patchTodoRequest.tags)
        }
        patchTodoRequest.assigneeId?.let { builder.setAssigneeId(it.toString()) }
        return ResponseEntity.ok(bridge.todoStub.patchTodo(builder.build()).toResponse())
    }

    override fun deleteTodo(id: UUID): ResponseEntity<Unit> {
        bridge.todoStub.deleteTodo(
            DeleteTodoRequest.newBuilder()
                .setId(id.toString())
                .setUserId(currentAuthenticatedUserId().toString())
                .build(),
        )
        return ResponseEntity.noContent().build()
    }
}
