package com.example.todo.domain.model

data class AdminSettings(
    val maxAllowedActiveTodos: Int = DEFAULT_MAX_ALLOWED_ACTIVE_TODOS,
    val allowUserRegistration: Boolean = true,
    val defaultPriority: TodoPriority = TodoPriority.MEDIUM,
    val defaultEstimatedMinutes: Int? = DEFAULT_ESTIMATED_MINUTES,
) {
    companion object {
        const val DEFAULT_MAX_ALLOWED_ACTIVE_TODOS = 100
        const val DEFAULT_ESTIMATED_MINUTES = 30

        fun default() = AdminSettings()
    }
}

