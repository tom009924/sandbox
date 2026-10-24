package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.infrastructure.persistence.SpringDataUserSettingsRepository
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
class UserSettingsControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataUserSettingsRepository: SpringDataUserSettingsRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val testUserId: UUID = UUID.randomUUID()
    private lateinit var token: String

    @BeforeEach
    fun cleanDatabase() {
        springDataUserSettingsRepository.deleteAll()
        token = jwtTokenProvider.generateToken(AuthenticatedUser(testUserId, "testuser"))
    }

    @Test
    fun `reject unauthenticated get settings request`() {
        mockMvc.perform(get("/api/user/settings"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `reject unauthenticated update settings request`() {
        mockMvc.perform(
            put("/api/user/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"HIGH"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `get settings returns default values when not configured`() {
        mockMvc.perform(
            get("/api/user/settings")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(testUserId.toString()))
            .andExpect(jsonPath("$.defaultPriority").value("MEDIUM"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").doesNotExist())
            .andExpect(jsonPath("$.maxActiveTodos").doesNotExist())
    }

    @Test
    fun `update and get settings`() {
        mockMvc.perform(
            put("/api/user/settings")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":45,"maxActiveTodos":5}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(testUserId.toString()))
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(45))
            .andExpect(jsonPath("$.maxActiveTodos").value(5))

        mockMvc.perform(
            get("/api/user/settings")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(45))
            .andExpect(jsonPath("$.maxActiveTodos").value(5))
    }

    @Test
    fun `update settings rejects invalid request body`() {
        mockMvc.perform(
            put("/api/user/settings")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"INVALID"}"""),
        ).andExpect(status().isBadRequest)
    }
}

