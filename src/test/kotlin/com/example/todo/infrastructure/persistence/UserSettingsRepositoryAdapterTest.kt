package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest
class UserSettingsRepositoryAdapterTest @Autowired constructor(
    private val adapter: UserSettingsRepositoryAdapter,
) {
    @Test
    fun `save and find user settings by userId`() {
        val userId = UUID.randomUUID()
        assertNull(adapter.findByUserId(userId))

        val settings = UserSettings(
            userId = userId,
            defaultPriority = TodoPriority.HIGH,
            defaultEstimatedMinutes = 60,
            maxActiveTodos = 10,
        )

        val saved = adapter.save(settings)
        assertEquals(userId, saved.userId)

        val found = adapter.findByUserId(userId)
        assertNotNull(found)
        assertEquals(TodoPriority.HIGH, found?.defaultPriority)
        assertEquals(60, found?.defaultEstimatedMinutes)
        assertEquals(10, found?.maxActiveTodos)

        val updated = adapter.save(settings.copy(maxActiveTodos = 20))
        assertEquals(20, adapter.findByUserId(userId)?.maxActiveTodos)
        assertEquals(20, updated.maxActiveTodos)
    }
}
