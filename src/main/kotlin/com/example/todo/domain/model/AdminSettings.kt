package com.example.todo.domain.model

data class AdminSettings(
    val maxAllowedActiveTodos: Int = DEFAULT_MAX_ALLOWED_ACTIVE_TODOS,
    val allowUserRegistration: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_ALLOWED_ACTIVE_TODOS = 100

        fun default() = AdminSettings()
    }
}

