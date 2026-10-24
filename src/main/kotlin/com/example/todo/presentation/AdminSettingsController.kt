package com.example.todo.presentation

import com.example.todo.application.AdminSettingsCommand
import com.example.todo.application.AdminSettingsService
import com.example.todo.domain.model.AdminSettings
import com.example.todo.presentation.api.AdminApi
import com.example.todo.presentation.model.AdminSettingsResponse
import com.example.todo.presentation.model.UpdateAdminSettingsRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminSettingsController(
    private val adminSettingsService: AdminSettingsService,
) : AdminApi {

    override fun getAdminSettings(): ResponseEntity<AdminSettingsResponse> {
        val settings = adminSettingsService.getSettings()
        return ResponseEntity.ok(settings.toResponse())
    }

    override fun updateAdminSettings(
        updateAdminSettingsRequest: UpdateAdminSettingsRequest,
    ): ResponseEntity<AdminSettingsResponse> {
        val command = AdminSettingsCommand(
            maxAllowedActiveTodos = updateAdminSettingsRequest.maxAllowedActiveTodos,
            allowUserRegistration = updateAdminSettingsRequest.allowUserRegistration,
        )
        val updated = adminSettingsService.updateSettings(command)
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun AdminSettings.toResponse() = AdminSettingsResponse(
        maxAllowedActiveTodos = maxAllowedActiveTodos,
        allowUserRegistration = allowUserRegistration,
    )
}

