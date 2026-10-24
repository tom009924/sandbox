package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.UserRole
import com.example.todo.infrastructure.persistence.SpringDataAdminSettingsRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class AdminSettingsControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataAdminSettingsRepository: SpringDataAdminSettingsRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun cleanDatabase() {
        springDataAdminSettingsRepository.deleteAll()
        userToken = jwtTokenProvider.generateToken(
            AuthenticatedUser(UUID.randomUUID(), "normaluser", UserRole.USER),
        )
        adminToken = jwtTokenProvider.generateToken(
            AuthenticatedUser(UUID.randomUUID(), "adminuser", UserRole.ADMIN),
        )
    }

    @Test
    fun `reject unauthenticated get settings request`() {
        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject unauthenticated update settings request`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":50,"allowUserRegistration":true,"defaultPriority":"HIGH"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject user role for get settings request`() {
        mockMvc.perform(
            get("/api/admin/settings")
                .header("Authorization", "Bearer $userToken"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `reject user role for update settings request`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header("Authorization", "Bearer $userToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":50,"allowUserRegistration":true,"defaultPriority":"HIGH"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `admin get settings returns default values when not configured`() {
        mockMvc.perform(
            get("/api/admin/settings")
                .header("Authorization", "Bearer $adminToken"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(EXPECTED_DEFAULT_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(true))
            .andExpect(jsonPath("$.defaultPriority").value("MEDIUM"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(EXPECTED_DEFAULT_MINUTES))
    }

    @Test
    fun `admin update and get settings`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"maxAllowedActiveTodos":50,"allowUserRegistration":false,""" +
                        """"defaultPriority":"HIGH","defaultEstimatedMinutes":45}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(UPDATED_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(false))
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(UPDATED_MINUTES))

        mockMvc.perform(
            get("/api/admin/settings")
                .header("Authorization", "Bearer $adminToken"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(UPDATED_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(false))
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(UPDATED_MINUTES))
    }

    @Test
    fun `update settings rejects invalid request body`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":0,"allowUserRegistration":true,"defaultPriority":"INVALID"}"""),
        ).andExpect(status().isBadRequest)
    }

    private companion object {
        const val EXPECTED_DEFAULT_MAX_TODOS = 100
        const val EXPECTED_DEFAULT_MINUTES = 30
        const val UPDATED_MAX_TODOS = 50
        const val UPDATED_MINUTES = 45
    }
}

