package com.example.todo

import com.example.todo.infrastructure.security.AdminApiKeyFilter.Companion.ADMIN_API_KEY_HEADER
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AdminStatsControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @Test
    fun `reject unauthenticated get stats request`() {
        mockMvc.perform(get("/api/admin/stats"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject invalid admin key for get stats request`() {
        mockMvc.perform(
            get("/api/admin/stats")
                .header(ADMIN_API_KEY_HEADER, INVALID_ADMIN_KEY),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `admin get stats returns count metrics`() {
        mockMvc.perform(
            get("/api/admin/stats")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalUsers").isNumber)
            .andExpect(jsonPath("$.totalTodos").isNumber)
            .andExpect(jsonPath("$.completedTodos").isNumber)
            .andExpect(jsonPath("$.activeTodos").isNumber)
    }

    private companion object {
        const val VALID_ADMIN_KEY = "admin-secret-key"
        const val INVALID_ADMIN_KEY = "wrong-admin-key"
    }
}

