package com.example.todo.infrastructure.security

import com.example.todo.application.UserContext
import com.example.todo.domain.model.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Component
class SpringSecurityUserContext : UserContext {
    override fun currentUserId(): UUID {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
        val principal = authentication.principal as? AuthenticatedUser
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated")
        return principal.userId
    }
}

