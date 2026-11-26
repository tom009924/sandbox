package com.example.identity.infrastructure.persistence

import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.UserSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@Tag("persistence")
@SpringBootTest(properties = ["identity.grpc.enabled=false"])
class UserSettingsRepositoryAdapterTest @Autowired constructor(
    private val adapter: UserSettingsRepositoryAdapter,
) {
    @Test
    fun saveAndFindUserSettingsByUserId() {
        val userId = UUID.randomUUID()
        assertNull(adapter.findByUserId(userId))

        val settings = UserSettings(
            userId = userId,
            defaultPriority = TodoPriority.HIGH,
            defaultEstimatedMinutes = DEFAULT_ESTIMATED_MINUTES,
            maxActiveTodos = INITIAL_MAX_ACTIVE_TODOS,
        )

        val saved = adapter.save(settings)
        assertEquals(userId, saved.userId)

        val found = adapter.findByUserId(userId)
        assertNotNull(found)
        assertEquals(TodoPriority.HIGH, found?.defaultPriority)
        assertEquals(DEFAULT_ESTIMATED_MINUTES, found?.defaultEstimatedMinutes)
        assertEquals(INITIAL_MAX_ACTIVE_TODOS, found?.maxActiveTodos)

        val updated = adapter.save(settings.copy(maxActiveTodos = UPDATED_MAX_ACTIVE_TODOS))
        assertEquals(UPDATED_MAX_ACTIVE_TODOS, adapter.findByUserId(userId)?.maxActiveTodos)
        assertEquals(UPDATED_MAX_ACTIVE_TODOS, updated.maxActiveTodos)
    }

    private companion object {
        const val DEFAULT_ESTIMATED_MINUTES = 60
        const val INITIAL_MAX_ACTIVE_TODOS = 10
        const val UPDATED_MAX_ACTIVE_TODOS = 20
    }
}
