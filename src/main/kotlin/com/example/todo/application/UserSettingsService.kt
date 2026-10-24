package com.example.todo.application

import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.UserSettingsRepository
import java.util.UUID

data class UserSettingsCommand(
    val defaultPriority: TodoPriority,
    val defaultEstimatedMinutes: Int?,
    val maxActiveTodos: Int?,
)

class UserSettingsService(
    private val userSettingsRepository: UserSettingsRepository,
) {
    fun getSettings(userId: UUID): UserSettings =
        userSettingsRepository.findByUserId(userId) ?: UserSettings.defaultFor(userId)

    fun updateSettings(userId: UUID, command: UserSettingsCommand): UserSettings {
        val settings = UserSettings(
            userId = userId,
            defaultPriority = command.defaultPriority,
            defaultEstimatedMinutes = command.defaultEstimatedMinutes,
            maxActiveTodos = command.maxActiveTodos,
        )
        return userSettingsRepository.save(settings)
    }
}

