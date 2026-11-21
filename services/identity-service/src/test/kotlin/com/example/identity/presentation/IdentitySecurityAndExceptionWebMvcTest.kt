package com.example.identity.presentation

import com.example.identity.application.AdminUserService
import com.example.identity.application.AuthService
import com.example.identity.application.UserContext
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.exception.InvalidCredentialsException
import com.example.identity.domain.exception.UserAlreadyExistsException
import com.example.identity.domain.model.AuthenticatedUser
import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.User
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserRepository
import com.example.identity.infrastructure.security.AdminApiKeyFilter
import com.example.identity.infrastructure.security.InternalApiKeyFilter
import com.example.identity.infrastructure.security.JwtTokenProvider
import com.example.identity.infrastructure.security.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [
        AdminUserController::class,
        AuthController::class,
        InternalIdentityController::class,
        UserSettingsController::class,
    ],
)
@Import(
    SecurityConfiguration::class,
    JwtTokenProvider::class,
    IdentityExceptionHandler::class,
)
class IdentitySecurityAndExceptionWebMvcTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val jwtTokenProvider: JwtTokenProvider,
) {
    @MockitoBean
    private lateinit var adminUserService: AdminUserService

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var userRepository: UserRepository

    @MockitoBean
    private lateinit var userSettingsService: UserSettingsService

    @MockitoBean
    private lateinit var userContext: UserContext

    @Test
    fun `admin endpoint enforces X-Admin-Key header`() {
        val userId = UUID.randomUUID()
        `when`(adminUserService.getUsers()).thenReturn(listOf(User(userId, "alice", "hash")))

        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/admin/users")
                .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "wrong-key"),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/admin/users")
                .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "admin-secret-key"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].username").value("alice"))
    }

    @Test
    fun `internal endpoint enforces X-Internal-Api-Key header`() {
        `when`(userRepository.count()).thenReturn(3L)

        mockMvc.perform(get("/internal/users/count"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/internal/users/count")
                .header(InternalApiKeyFilter.INTERNAL_API_KEY_HEADER, "wrong-key"),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/internal/users/count")
                .header(InternalApiKeyFilter.INTERNAL_API_KEY_HEADER, "internal-secret-key"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(3))
    }

    @Test
    fun `user settings endpoint requires valid JWT`() {
        val userId = UUID.randomUUID()
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, "alice"))
        `when`(userContext.currentUserId()).thenReturn(userId)
        `when`(userSettingsService.getSettings(userId)).thenReturn(
            UserSettings(userId = userId, defaultPriority = TodoPriority.HIGH),
        )

        mockMvc.perform(get("/api/user/settings"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/user/settings")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
    }

    @Test
    fun `exception handler maps domain and validation errors consistently`() {
        `when`(authService.register("alice", "Password123!")).thenThrow(UserAlreadyExistsException("alice"))
        `when`(authService.login("alice", "wrong")).thenThrow(InvalidCredentialsException())

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"Password123!"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("User with username 'alice' already exists"))

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.message").value("Invalid username or password"))

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{malformed-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Invalid request parameters"))
    }
}
