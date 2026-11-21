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

class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenIssuer: TokenIssuer,
    private val emailNotificationSender: EmailNotificationSender? = null,
) {
    fun register(
        username: String,
        rawPassword: String,
    ): User {
        val trimmedUsername = username.trim()
        if (userRepository.existsByUsername(trimmedUsername)) {
            throw UserAlreadyExistsException(trimmedUsername)
        }
        val passwordHash = passwordEncoder.encode(rawPassword)
        val savedUser = userRepository.save(
            User(
                username = trimmedUsername,
                passwordHash = passwordHash,
            ),
        )
        try {
            emailNotificationSender?.send(
                EmailNotification(
                    to = "$trimmedUsername@example.com",
                    subject = "Welcome to Todo App",
                    content = "Welcome to Todo App, $trimmedUsername!",
                ),
            )
        } catch (_: EmailNotificationException) {
            // Best-effort notification: do not fail user registration on email service failure
        }
        return savedUser
    }

    fun login(username: String, rawPassword: String): TokenResult {
        val user = userRepository.findByUsername(username.trim())
            ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(rawPassword, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        val userId = requireNotNull(user.id) { "User id must not be null" }
        return tokenIssuer.issueToken(AuthenticatedUser(userId, user.username))
    }
}
