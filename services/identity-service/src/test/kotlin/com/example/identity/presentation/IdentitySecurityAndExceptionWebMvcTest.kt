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
import com.example.identity.infrastructure.security.AdminApiKeyFilter
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [
        AdminUserController::class,
        AuthController::class,
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

    @Test
    fun `validates password minimum length of 6 and zero estimated minutes`() {
        val userId = UUID.randomUUID()
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, "bob"))
        `when`(authService.register("bob", "short6")).thenReturn(User(userId, "bob", "hash"))
        `when`(userContext.currentUserId()).thenReturn(userId)
        `when`(
            userSettingsService.updateSettings(
                userId,
                com.example.identity.application.UserSettingsCommand(
                    defaultPriority = TodoPriority.MEDIUM,
                    defaultEstimatedMinutes = 0,
                    maxActiveTodos = 5,
                ),
            ),
        ).thenReturn(UserSettings(userId, TodoPriority.MEDIUM, 0, 5))

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"short6"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"12345"}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            put("/api/user/settings")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"MEDIUM","defaultEstimatedMinutes":0,"maxActiveTodos":5}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(0))
    }
}
