package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.TodoPriority
import com.example.todo.infrastructure.persistence.SpringDataTodoRepository
import com.example.todo.infrastructure.persistence.SpringDataTodoSubtaskRepository
import com.example.todo.infrastructure.persistence.TodoSpannerEntity
import com.example.todo.infrastructure.security.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class TodoSubtaskControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataTodoSubtaskRepository: SpringDataTodoSubtaskRepository,
    private val springDataTodoRepository: SpringDataTodoRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private lateinit var userId: UUID
    private lateinit var todoId: UUID
    private lateinit var token: String

    @BeforeEach
    fun cleanDatabase() {
        springDataTodoSubtaskRepository.deleteAll()
        springDataTodoRepository.deleteAll()
        userId = UUID.randomUUID()
        todoId = UUID.randomUUID()
        springDataTodoRepository.save(
            TodoSpannerEntity(
                id = todoId.toString(),
                userId = userId.toString(),
                title = "Task",
                priority = TodoPriority.MEDIUM.name,
            ),
        )
        token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, "alice"))
    }

    @Test
    fun `reject unauthenticated list subtasks request`() {
        mockMvc.perform(get("/api/todos/$todoId/subtasks"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `create, list, patch, and delete subtask for authenticated user`() {
        val createdBody = mockMvc.perform(
            post("/api/todos/$todoId/subtasks")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Check items"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Check items"))
            .andExpect(jsonPath("$.completed").value(false))
            .andReturn().response.contentAsString

        val subtaskId = Regex(""""id":"([^"]+)"""").find(createdBody)?.groupValues?.get(1)

        mockMvc.perform(
            get("/api/todos/$todoId/subtasks")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        mockMvc.perform(
            patch("/api/todos/$todoId/subtasks/$subtaskId")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"completed":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.completed").value(true))

        mockMvc.perform(
            delete("/api/todos/$todoId/subtasks/$subtaskId")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isNoContent)
    }
}
