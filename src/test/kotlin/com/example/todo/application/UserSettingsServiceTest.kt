package com.example.todo.application

import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.UserSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class UserSettingsServiceTest {
    private val repository = InMemoryUserSettingsRepository()
    private val service = UserSettingsService(repository)
    private val userId = UUID.randomUUID()

    @Test
    fun `getSettings returns defaults when no settings exist`() {
        val settings = service.getSettings(userId)
        assertEquals(userId, settings.userId)
        assertEquals(TodoPriority.MEDIUM, settings.defaultPriority)
        assertNull(settings.defaultEstimatedMinutes)
        assertNull(settings.maxActiveTodos)
    }

    @Test
    fun `updateSettings saves and returns updated settings`() {
        val command = UserSettingsCommand(
            defaultPriority = TodoPriority.HIGH,
            defaultEstimatedMinutes = 45,
            maxActiveTodos = 5,
        )
        val updated = service.updateSettings(userId, command)

        assertEquals(userId, updated.userId)
        assertEquals(TodoPriority.HIGH, updated.defaultPriority)
        assertEquals(45, updated.defaultEstimatedMinutes)
        assertEquals(5, updated.maxActiveTodos)

        val retrieved = service.getSettings(userId)
        assertEquals(updated, retrieved)
    }

    private class InMemoryUserSettingsRepository : UserSettingsRepository {
        private val storage = mutableMapOf<UUID, UserSettings>()

        override fun findByUserId(userId: UUID): UserSettings? = storage[userId]

        override fun save(userSettings: UserSettings): UserSettings {
            storage[userSettings.userId] = userSettings
            return userSettings
        }
    }
}

