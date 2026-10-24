package com.example.todo.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class AdminApiKeyFilter(
    private val expectedApiKey: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/admin")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKey = request.getHeader(ADMIN_API_KEY_HEADER)
        if (apiKey.isNullOrBlank()) {
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "Missing or invalid $ADMIN_API_KEY_HEADER header")
        } else if (apiKey != expectedApiKey) {
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Access denied. Invalid $ADMIN_API_KEY_HEADER.")
        } else {
            val authorities = listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                "admin",
                null,
                authorities,
            )
            filterChain.doFilter(request, response)
        }
    }

    private fun sendErrorResponse(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body = mapOf(
            "status" to status.value(),
            "error" to status.reasonPhrase,
            "message" to message,
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    companion object {
        const val ADMIN_API_KEY_HEADER = "X-Admin-Key"
    }
}

