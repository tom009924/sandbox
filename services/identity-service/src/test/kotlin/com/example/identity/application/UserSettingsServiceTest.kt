package com.example.identity.application

import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class UserSettingsServiceTest {
    private val repository = object : UserSettingsRepository {
        private val store = HashMap<UUID, UserSettings>()
        override fun findByUserId(userId: UUID): UserSettings? = store[userId]
        override fun save(userSettings: UserSettings): UserSettings {
            store[userSettings.userId] = userSettings
            return userSettings
        }
    }
    private val service = UserSettingsService(repository)

    @Test
    fun `getSettings returns default when none saved`() {
        val userId = UUID.randomUUID()

        val settings = service.getSettings(userId)

        assertEquals(userId, settings.userId)
        assertEquals(TodoPriority.MEDIUM, settings.defaultPriority)
        assertNull(settings.defaultEstimatedMinutes)
        assertNull(settings.maxActiveTodos)
    }

    @Test
    fun `updateSettings persists and returns updated settings`() {
        val userId = UUID.randomUUID()
        val command = UserSettingsCommand(
            defaultPriority = TodoPriority.HIGH,
            defaultEstimatedMinutes = 30,
            maxActiveTodos = 10,
        )

        val updated = service.updateSettings(userId, command)
        val fetched = service.getSettings(userId)

        assertEquals(TodoPriority.HIGH, updated.defaultPriority)
        assertEquals(30, fetched.defaultEstimatedMinutes)
        assertEquals(10, fetched.maxActiveTodos)
    }
}
