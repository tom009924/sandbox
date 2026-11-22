package com.example.gateway.presentation

import com.example.gateway.security.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

internal const val NON_WHITESPACE_PATTERN = ".*\\S.*"
internal const val NO_WHITESPACE_PATTERN = "^\\S+$"
internal const val NO_CONTROL_CHARS_PATTERN = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$"

internal const val MAX_USERNAME_LENGTH = 50
internal const val MIN_USERNAME_LENGTH = 3
internal const val MIN_PASSWORD_LENGTH = 6
internal const val MAX_PASSWORD_LENGTH = 100
internal const val MAX_LOGIN_FIELD_LENGTH = 255
internal const val MAX_TITLE_LENGTH = 100
internal const val MAX_DESCRIPTION_LENGTH = 500
internal const val MAX_TAG_LENGTH = 32
internal const val MAX_TAGS_COUNT = 10
internal const val MAX_COMMENT_LENGTH = 1000
internal const val MAX_PROJECT_MEMBER_USERNAME_LENGTH = 255
internal const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

internal fun currentUserId(): UUID {
    val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
    return principal.userId
}
