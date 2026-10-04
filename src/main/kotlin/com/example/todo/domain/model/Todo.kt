package com.example.todo.domain.model

import java.util.UUID

data class Todo(
    val id: UUID? = null,
    val title: String,
    val completed: Boolean = false,
)
