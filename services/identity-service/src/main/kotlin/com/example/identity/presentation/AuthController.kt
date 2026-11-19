package com.example.identity.presentation

import com.example.identity.application.AuthService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    val username: String,
    @field:NotBlank
    @field:Size(min = 8, max = 100)
    val password: String,
)

data class LoginRequest(
    @field:NotBlank
    val username: String,
    @field:NotBlank
    val password: String,
)

data class UserResponse(
    val id: UUID,
    val username: String,
)

data class AuthTokenResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/register")
    @Transactional
    fun registerUser(
        @Valid @RequestBody request: RegisterRequest,
    ): ResponseEntity<UserResponse> {
        val user = authService.register(request.username, request.password)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(
                id = requireNotNull(user.id) { "User id must not be null" },
                username = user.username,
            ),
        )
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    fun loginUser(
        @Valid @RequestBody request: LoginRequest,
    ): ResponseEntity<AuthTokenResponse> {
        val tokenResult = authService.login(request.username, request.password)
        return ResponseEntity.ok(
            AuthTokenResponse(
                token = tokenResult.token,
                tokenType = tokenResult.tokenType,
                expiresIn = tokenResult.expiresIn,
            ),
        )
    }
}
