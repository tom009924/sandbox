package com.example.identity.application

import com.example.identity.domain.exception.InvalidCredentialsException
import com.example.identity.domain.exception.UserAlreadyExistsException
import com.example.identity.domain.model.AuthenticatedUser
import com.example.identity.domain.model.User
import com.example.identity.domain.notification.EmailNotification
import com.example.identity.domain.notification.EmailNotificationException
import com.example.identity.domain.notification.EmailNotificationSender
import com.example.identity.domain.repository.UserRepository
import com.example.identity.domain.security.PasswordEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthServiceTest {
    private val userRepository = InMemoryUserRepository()
    private val passwordEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: String): String = "encoded:$rawPassword"
        override fun matches(rawPassword: String, encodedPassword: String): Boolean =
            encodedPassword == "encoded:$rawPassword"
    }
    private val tokenIssuer = object : TokenIssuer {
        override fun issueToken(user: AuthenticatedUser): TokenResult = TokenResult(
            token = "token-for-${user.username}",
            tokenType = "Bearer",
            expiresIn = 3600L,
        )
    }

    @Test
    fun `register saves user and sends welcome email`() {
        val sentEmails = mutableListOf<EmailNotification>()
        val sender = object : EmailNotificationSender {
            override fun send(notification: EmailNotification) {
                sentEmails.add(notification)
            }
        }
        val service = AuthService(userRepository, passwordEncoder, tokenIssuer, sender)

        val user = service.register("  alice  ", "secret123")

        assertNotNull(user.id)
        assertEquals("alice", user.username)
        assertEquals("encoded:secret123", user.passwordHash)
        assertEquals(1, sentEmails.size)
        assertEquals("alice@example.com", sentEmails.first().to)
    }

    @Test
    fun `register succeeds even if email notification fails`() {
        val failingSender = object : EmailNotificationSender {
            override fun send(notification: EmailNotification) {
                throw EmailNotificationException("SMTP down")
            }
        }
        val service = AuthService(userRepository, passwordEncoder, tokenIssuer, failingSender)

        val user = service.register("bob", "secret123")

        assertEquals("bob", user.username)
    }

    @Test
    fun `register throws when username already exists`() {
        val service = AuthService(userRepository, passwordEncoder, tokenIssuer)
        service.register("alice", "secret123")

        assertThrows(UserAlreadyExistsException::class.java) {
            service.register("alice", "other123")
        }
    }

    @Test
    fun `login returns token for valid credentials`() {
        val service = AuthService(userRepository, passwordEncoder, tokenIssuer)
        service.register("alice", "secret123")

        val result = service.login("alice", "secret123")

        assertEquals("token-for-alice", result.token)
        assertEquals("Bearer", result.tokenType)
    }

    @Test
    fun `login throws for invalid password`() {
        val service = AuthService(userRepository, passwordEncoder, tokenIssuer)
        service.register("alice", "secret123")

        assertThrows(InvalidCredentialsException::class.java) {
            service.login("alice", "wrong")
        }
    }

    private class InMemoryUserRepository : UserRepository {
        private val store = LinkedHashMap<UUID, User>()

        override fun findById(id: UUID): User? = store[id]
        override fun findAllByIdIn(ids: Collection<UUID>): List<User> = ids.mapNotNull { store[it] }
        override fun findAll(): List<User> = store.values.toList()
        override fun findByUsername(username: String): User? = store.values.firstOrNull { it.username == username }
        override fun existsByUsername(username: String): Boolean = store.values.any { it.username == username }
        override fun save(user: User): User {
            val id = user.id ?: UUID.randomUUID()
            val saved = user.copy(id = id)
            store[id] = saved
            return saved
        }
        override fun count(): Long = store.size.toLong()
        override fun deleteAll() = store.clear()
    }
}
