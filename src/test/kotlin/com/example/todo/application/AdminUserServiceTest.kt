package com.example.todo.application

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.UUID

class AdminUserServiceTest {
    private val userRepository = mock(UserRepository::class.java)
    private val adminUserService = AdminUserService(userRepository)

    @Test
    fun `getUsers returns list of users from repository`() {
        val user1 = User(id = UUID.randomUUID(), username = "user1", passwordHash = "hash1")
        val user2 = User(id = UUID.randomUUID(), username = "user2", passwordHash = "hash2")
        `when`(userRepository.findAll()).thenReturn(listOf(user1, user2))

        val result = adminUserService.getUsers()

        assertEquals(2, result.size)
        assertEquals("user1", result[0].username)
        assertEquals("user2", result[1].username)
    }
}

