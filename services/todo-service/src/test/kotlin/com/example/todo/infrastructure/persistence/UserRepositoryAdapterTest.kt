package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest
class UserRepositoryAdapterTest @Autowired constructor(
    private val adapter: UserRepositoryAdapter,
) {
    @BeforeEach
    fun cleanDatabase() {
        adapter.deleteAll()
    }

    @Test
    fun `save and find user by id and username`() {
        val username = "user_${UUID.randomUUID().toString().take(8)}"
        val user = User(
            id = null,
            username = username,
            passwordHash = "hashed_password",
        )

        val saved = adapter.save(user)
        val userId = requireNotNull(saved.id)

        val byId = adapter.findById(userId)
        assertNotNull(byId)
        assertEquals(username, byId?.username)

        val byUsername = adapter.findByUsername(username)
        assertNotNull(byUsername)
        assertEquals(userId, byUsername?.id)

        assertTrue(adapter.existsByUsername(username))
        assertFalse(adapter.existsByUsername("non_existent_user"))

        val updated = adapter.save(saved.copy(passwordHash = "new_hash"))
        assertEquals("new_hash", adapter.findById(userId)?.passwordHash)
        assertEquals(userId, updated.id)

        assertTrue(adapter.count() >= 1L)
        assertTrue(adapter.findAll().any { it.id == userId })
    }

    @Test
    fun `findById returns null when user not found`() {
        assertNull(adapter.findById(UUID.randomUUID()))
        assertNull(adapter.findByUsername("unknown_${UUID.randomUUID()}"))
    }
}
