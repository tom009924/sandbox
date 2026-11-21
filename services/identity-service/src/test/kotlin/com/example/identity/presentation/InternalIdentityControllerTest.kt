package com.example.identity.presentation

import com.example.identity.application.UserSettingsService
import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.User
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserRepository
import com.example.identity.domain.repository.UserSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

class InternalIdentityControllerTest {
    private val userId = UUID.randomUUID()
    private val user = User(id = userId, username = "alice", passwordHash = "hash")

    private val userRepository = object : UserRepository {
        override fun findById(id: UUID): User? = if (id == userId) user else null
        override fun findAllByIdIn(ids: Collection<UUID>): List<User> = if (userId in ids) listOf(user) else emptyList()
        override fun findAll(): List<User> = listOf(user)
        override fun findByUsername(username: String): User? = if (username == "alice") user else null
        override fun existsByUsername(username: String): Boolean = username == "alice"
        override fun save(user: User): User = user
        override fun count(): Long = 1L
        override fun deleteAll() = Unit
    }

    private val userSettingsRepository = object : UserSettingsRepository {
        override fun findByUserId(userId: UUID): UserSettings? =
            if (userId == this@InternalIdentityControllerTest.userId) {
                UserSettings(userId = userId, defaultPriority = TodoPriority.HIGH, maxActiveTodos = 5)
            } else {
                null
            }

        override fun save(userSettings: UserSettings): UserSettings = userSettings
    }

    private val controller = InternalIdentityController(
        userRepository = userRepository,
        userSettingsService = UserSettingsService(userSettingsRepository),
    )

    @Test
    fun `getUserById returns user when found and 404 when missing`() {
        val found = controller.getUserById(userId)
        assertEquals(HttpStatus.OK, found.statusCode)
        assertEquals("alice", found.body?.username)

        val missing = controller.getUserById(UUID.randomUUID())
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
    }

    @Test
    fun `getUserByUsername and countUsers and getUserSettings return expected values`() {
        val byUsername = controller.getUserByUsername(" alice ")
        assertEquals(HttpStatus.OK, byUsername.statusCode)
        assertEquals(userId, byUsername.body?.id)

        val count = controller.countUsers()
        assertEquals(1L, count.body?.count)

        val settings = controller.getUserSettings(userId)
        assertEquals(TodoPriority.HIGH, settings.body?.defaultPriority)
        assertEquals(5, settings.body?.maxActiveTodos)
    }
}
