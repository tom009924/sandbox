package com.example.todo

import com.example.todo.infrastructure.persistence.SpringDataUserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataUserRepository: SpringDataUserRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataUserRepository.deleteAll()
    }

    @Test
    fun `register user returns created user and prevents duplicate username`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"password123"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.username").value("alice"))

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"password456"}"""),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `login returns jwt token for valid credentials and rejects invalid credentials`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"password123"}"""),
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"password123"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(86400))

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"wrongpassword"}"""),
        )
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"unknown","password":"password123"}"""),
        )
            .andExpect(status().isUnauthorized)
    }
}

