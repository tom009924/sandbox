package com.example.todo.application

import com.example.todo.domain.model.AdminSettings
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.AdminSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminSettingsServiceTest {
    private val repository = InMemoryAdminSettingsRepository()
    private val service = AdminSettingsService(repository)

    @Test
    fun `getSettings returns defaults when no settings exist`() {
        val settings = service.getSettings()
        assertEquals(AdminSettings.DEFAULT_MAX_ALLOWED_ACTIVE_TODOS, settings.maxAllowedActiveTodos)
        assertTrue(settings.allowUserRegistration)
        assertEquals(TodoPriority.MEDIUM, settings.defaultPriority)
        assertEquals(AdminSettings.DEFAULT_ESTIMATED_MINUTES, settings.defaultEstimatedMinutes)
    }

    @Test
    fun `updateSettings saves and returns updated settings`() {
        val command = AdminSettingsCommand(
            maxAllowedActiveTodos = 50,
            allowUserRegistration = false,
            defaultPriority = TodoPriority.HIGH,
            defaultEstimatedMinutes = 60,
        )
        val updated = service.updateSettings(command)

        assertEquals(50, updated.maxAllowedActiveTodos)
        assertFalse(updated.allowUserRegistration)
        assertEquals(TodoPriority.HIGH, updated.defaultPriority)
        assertEquals(60, updated.defaultEstimatedMinutes)

        val retrieved = service.getSettings()
        assertEquals(updated, retrieved)
    }

    private class InMemoryAdminSettingsRepository : AdminSettingsRepository {
        private var storage: AdminSettings? = null

        override fun find(): AdminSettings? = storage

        override fun save(settings: AdminSettings): AdminSettings {
            storage = settings
            return settings
        }

        override fun deleteAll() {
            storage = null
        }
    }
}

