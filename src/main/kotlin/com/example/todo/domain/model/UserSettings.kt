package com.example.todo.domain.model

import java.util.UUID

data class UserSettings(
    val userId: UUID,
    val defaultPriority: TodoPriority = TodoPriority.MEDIUM,
    val defaultEstimatedMinutes: Int? = null,
    val maxActiveTodos: Int? = null,
) {
    companion object {
        fun defaultFor(userId: UUID) = UserSettings(userId = userId)
    }
}

