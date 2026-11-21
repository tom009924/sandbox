package com.example.identity.presentation

import com.example.identity.application.UserContext
import com.example.identity.application.UserSettingsCommand
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.UserSettings
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class UpdateUserSettingsRequest(
    val defaultPriority: TodoPriority = TodoPriority.MEDIUM,
    @field:Min(1)
    val defaultEstimatedMinutes: Int? = null,
    @field:Min(1)
    val maxActiveTodos: Int? = null,
)

data class UserSettingsResponse(
    val userId: UUID,
    val defaultPriority: TodoPriority,
    val defaultEstimatedMinutes: Int?,
    val maxActiveTodos: Int?,
)

@RestController
@RequestMapping("/api/user/settings")
class UserSettingsController(
    private val userSettingsService: UserSettingsService,
    private val userContext: UserContext,
) {
    @GetMapping
    @Transactional(readOnly = true)
    fun getUserSettings(): ResponseEntity<UserSettingsResponse> {
        val userId = userContext.currentUserId()
        val settings = userSettingsService.getSettings(userId)
        return ResponseEntity.ok(settings.toResponse())
    }

    @PutMapping
    @Transactional
    fun updateUserSettings(
        @Valid @RequestBody request: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        val userId = userContext.currentUserId()
        val command = UserSettingsCommand(
            defaultPriority = request.defaultPriority,
            defaultEstimatedMinutes = request.defaultEstimatedMinutes,
            maxActiveTodos = request.maxActiveTodos,
        )
        val updated = userSettingsService.updateSettings(userId, command)
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun UserSettings.toResponse() = UserSettingsResponse(
        userId = userId,
        defaultPriority = defaultPriority,
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )
}
