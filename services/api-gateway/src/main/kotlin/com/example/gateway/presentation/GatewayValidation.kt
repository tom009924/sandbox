package com.example.gateway.presentation

import com.example.gateway.security.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private const val MAX_TAG_LENGTH = 32

internal fun currentUserId(): UUID {
    val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
    return principal.userId
}

internal fun validateTagItems(tags: List<String>?) {
    if (tags != null && tags.any { it.length > MAX_TAG_LENGTH }) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Each tag must be at most $MAX_TAG_LENGTH characters",
        )
    }
}
