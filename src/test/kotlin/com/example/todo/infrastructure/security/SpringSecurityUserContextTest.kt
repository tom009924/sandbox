package com.example.todo.infrastructure.security

import com.example.todo.domain.model.AuthenticatedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class SpringSecurityUserContextTest {
    private val userContext = SpringSecurityUserContext()

    @AfterEach
    fun cleanContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `currentUserId returns authenticated user id`() {
        val userId = UUID.randomUUID()
        val user = AuthenticatedUser(userId = userId, username = "testuser")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, null, emptyList())

        assertEquals(userId, userContext.currentUserId())
    }

    @Test
    fun `currentUserId throws 401 when no authentication present`() {
        val ex = assertThrows<ResponseStatusException> {
            userContext.currentUserId()
        }
        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
    }

    @Test
    fun `currentUserId throws 401 when principal is not an AuthenticatedUser`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("anonymousUser", null, emptyList())

        val ex = assertThrows<ResponseStatusException> {
            userContext.currentUserId()
        }
        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
    }
}

