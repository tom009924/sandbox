package com.example.todo.domain.event

import com.example.todo.domain.model.Todo
import java.time.Instant
import java.util.UUID

data class TodoEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: TodoEventType,
    val timestamp: Instant,
    val todoId: UUID,
    val todo: Todo? = null,
)

