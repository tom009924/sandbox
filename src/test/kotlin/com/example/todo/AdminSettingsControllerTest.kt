package com.example.todo

import com.example.todo.infrastructure.persistence.SpringDataAdminSettingsRepository
import com.example.todo.infrastructure.security.AdminApiKeyFilter.Companion.ADMIN_API_KEY_HEADER
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

@SpringBootTest
@AutoConfigureMockMvc
class AdminSettingsControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataAdminSettingsRepository: SpringDataAdminSettingsRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataAdminSettingsRepository.deleteAll()
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
                .content("""{"maxAllowedActiveTodos":50,"allowUserRegistration":true}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject invalid admin key for get settings request`() {
        mockMvc.perform(
            get("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, INVALID_ADMIN_KEY),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `reject invalid admin key for update settings request`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, INVALID_ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":50,"allowUserRegistration":true}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `admin get settings returns default values when not configured`() {
        mockMvc.perform(
            get("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(EXPECTED_DEFAULT_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(true))
    }

    @Test
    fun `admin update and get settings`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":50,"allowUserRegistration":false}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(UPDATED_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(false))

        mockMvc.perform(
            get("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maxAllowedActiveTodos").value(UPDATED_MAX_TODOS))
            .andExpect(jsonPath("$.allowUserRegistration").value(false))
    }

    @Test
    fun `update settings rejects invalid request body`() {
        mockMvc.perform(
            put("/api/admin/settings")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"maxAllowedActiveTodos":0,"allowUserRegistration":true}"""),
        ).andExpect(status().isBadRequest)
    }

    private companion object {
        const val VALID_ADMIN_KEY = "admin-secret-key"
        const val INVALID_ADMIN_KEY = "wrong-admin-key"
        const val EXPECTED_DEFAULT_MAX_TODOS = 100
        const val UPDATED_MAX_TODOS = 50
    }
}
