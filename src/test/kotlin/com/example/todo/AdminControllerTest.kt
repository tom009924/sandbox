package com.example.todo

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.security.AdminApiKeyFilter.Companion.ADMIN_API_KEY_HEADER
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
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

    @Test
    fun `reject unauthenticated get users request`() {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject invalid admin key for get users request`() {
        mockMvc.perform(
            get("/api/admin/users")
                .header(ADMIN_API_KEY_HEADER, INVALID_ADMIN_KEY),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `admin get users returns registered users list`() {
        userRepository.save(User(username = "admin_test_user", passwordHash = "hash"))

        mockMvc.perform(
            get("/api/admin/users")
                .header(ADMIN_API_KEY_HEADER, VALID_ADMIN_KEY),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].id").isNotEmpty)
            .andExpect(jsonPath("$[0].username").isNotEmpty)
    }

    private companion object {
        const val VALID_ADMIN_KEY = "admin-secret-key"
        const val INVALID_ADMIN_KEY = "wrong-admin-key"
    }
}

