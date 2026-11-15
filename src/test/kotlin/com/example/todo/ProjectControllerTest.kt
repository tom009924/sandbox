package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.infrastructure.persistence.SpringDataProjectMemberRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class ProjectControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataProjectRepository: SpringDataProjectRepository,
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val testUserId: UUID = UUID.randomUUID()
    private lateinit var token: String

    @BeforeEach
    fun cleanDatabase() {
        springDataProjectMemberRepository.deleteAll()
        springDataProjectRepository.deleteAll()
        token = jwtTokenProvider.generateToken(AuthenticatedUser(testUserId, "alice"))
    }

    @Test
    fun `reject unauthenticated list projects request`() {
        mockMvc.perform(get("/api/projects"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `create and list projects for authenticated user`() {
        mockMvc.perform(
            post("/api/projects")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Backend","description":"Shared work"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Backend"))
            .andExpect(jsonPath("$.createdBy").value(testUserId.toString()))

        mockMvc.perform(
            get("/api/projects")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Backend"))
    }
}
