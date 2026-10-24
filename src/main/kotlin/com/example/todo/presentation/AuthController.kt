package com.example.todo.presentation

import com.example.todo.application.AuthService
import com.example.todo.presentation.api.AuthApi
import com.example.todo.presentation.model.AuthTokenResponse
import com.example.todo.presentation.model.LoginRequest
import com.example.todo.presentation.model.RegisterRequest
import com.example.todo.presentation.model.UserResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import com.example.todo.domain.model.UserRole as DomainUserRole
import com.example.todo.presentation.model.UserRole as ApiUserRole

@RestController
class AuthController(
    private val authService: AuthService,
) : AuthApi {
    override fun registerUser(registerRequest: RegisterRequest): ResponseEntity<UserResponse> {
        val role = registerRequest.role?.let { DomainUserRole.valueOf(it.name) } ?: DomainUserRole.USER
        val user = authService.register(registerRequest.username, registerRequest.password, role)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(
                id = requireNotNull(user.id) { "User id must not be null" },
                username = user.username,
                role = ApiUserRole.valueOf(user.role.name),
            ),
        )
    }

    override fun loginUser(loginRequest: LoginRequest): ResponseEntity<AuthTokenResponse> {
        val tokenResult = authService.login(loginRequest.username, loginRequest.password)
        return ResponseEntity.ok(
            AuthTokenResponse(
                token = tokenResult.token,
                tokenType = tokenResult.tokenType,
                expiresIn = tokenResult.expiresIn,
            ),
        )
    }
}

