package com.example.identity.infrastructure.persistence

import com.example.identity.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@Tag("persistence")
@SpringBootTest(properties = ["identity.grpc.enabled=false"])
class UserRepositoryAdapterTest @Autowired constructor(
    private val adapter: UserRepositoryAdapter,
) {
    @BeforeEach
    fun cleanDatabase() {
        adapter.deleteAll()
    }

    @Test
    fun saveAndFindUserByIdUsernameAndBatchIds() {
        val username = "id_user_${UUID.randomUUID().toString().take(USERNAME_SUFFIX_LENGTH)}"
        val saved = adapter.save(
            User(
                id = null,
                username = username,
                passwordHash = "hashed_password",
            ),
        )
        val userId = requireNotNull(saved.id)

        val byId = adapter.findById(userId)
        assertNotNull(byId)
        assertEquals(username, byId?.username)

        val byUsername = adapter.findByUsername(username)
        assertNotNull(byUsername)
        assertEquals(userId, byUsername?.id)

        assertEquals(listOf(saved), adapter.findAllByIdIn(listOf(userId)))
        assertEquals(emptyList<User>(), adapter.findAllByIdIn(emptyList()))

        assertTrue(adapter.existsByUsername(username))
        assertFalse(adapter.existsByUsername("non_existent_user"))

        val updated = adapter.save(saved.copy(passwordHash = "new_hash"))
        assertEquals("new_hash", adapter.findById(userId)?.passwordHash)
        assertEquals(userId, updated.id)

        assertTrue(adapter.count() >= 1L)
        assertTrue(adapter.findAll().any { it.id == userId })
    }

    @Test
    fun findByIdAndUsernameReturnNullWhenMissing() {
        assertNull(adapter.findById(UUID.randomUUID()))
        assertNull(adapter.findByUsername("unknown_${UUID.randomUUID()}"))
    }

    private companion object {
        const val USERNAME_SUFFIX_LENGTH = 8
    }
}
