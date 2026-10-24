package com.example.todo.application

import com.example.todo.domain.model.AdminSettings
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.AdminSettingsRepository

data class AdminSettingsCommand(
    val maxAllowedActiveTodos: Int,
    val allowUserRegistration: Boolean,
    val defaultPriority: TodoPriority,
    val defaultEstimatedMinutes: Int? = null,
)

class AdminSettingsService(
    private val adminSettingsRepository: AdminSettingsRepository,
) {
    fun getSettings(): AdminSettings =
        adminSettingsRepository.find() ?: AdminSettings.default()

    fun updateSettings(command: AdminSettingsCommand): AdminSettings {
        val settings = AdminSettings(
            maxAllowedActiveTodos = command.maxAllowedActiveTodos,
            allowUserRegistration = command.allowUserRegistration,
            defaultPriority = command.defaultPriority,
            defaultEstimatedMinutes = command.defaultEstimatedMinutes,
        )
        return adminSettingsRepository.save(settings)
    }
}

