package com.example.todo.presentation

import com.example.todo.application.UserContext
import com.example.todo.application.UserSettingsCommand
import com.example.todo.application.UserSettingsService
import com.example.todo.domain.model.UserSettings
import com.example.todo.presentation.api.SettingsApi
import com.example.todo.presentation.model.UpdateUserSettingsRequest
import com.example.todo.presentation.model.UserSettingsResponse
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import com.example.todo.domain.model.TodoPriority as DomainTodoPriority
import com.example.todo.presentation.model.TodoPriority as ApiTodoPriority

@RestController
class UserSettingsController(
    private val userSettingsService: UserSettingsService,
    private val userContext: UserContext,
) : SettingsApi {

    @Transactional(readOnly = true)
    override fun getUserSettings(): ResponseEntity<UserSettingsResponse> {
        val userId = userContext.currentUserId()
        val settings = userSettingsService.getSettings(userId)
        return ResponseEntity.ok(settings.toResponse())
    }

    @Transactional
    override fun updateUserSettings(
        updateUserSettingsRequest: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val userId = userContext.currentUserId()
        val command = UserSettingsCommand(
            defaultPriority = DomainTodoPriority.valueOf(updateUserSettingsRequest.defaultPriority.name),
            defaultEstimatedMinutes = updateUserSettingsRequest.defaultEstimatedMinutes,
            maxActiveTodos = updateUserSettingsRequest.maxActiveTodos,
        )
        val updated = userSettingsService.updateSettings(userId, command)
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun UserSettings.toResponse() = UserSettingsResponse(
        userId = userId,
        defaultPriority = ApiTodoPriority.valueOf(defaultPriority.name),
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )
}

