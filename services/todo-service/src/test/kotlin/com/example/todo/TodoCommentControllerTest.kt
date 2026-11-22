package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.TodoPriority
import com.example.todo.infrastructure.persistence.SpringDataTodoCommentRepository
import com.example.todo.infrastructure.persistence.SpringDataTodoRepository
import com.example.todo.infrastructure.persistence.SpringDataUserRepository
import com.example.todo.infrastructure.persistence.TodoSpannerEntity
import com.example.todo.infrastructure.persistence.UserSpannerEntity
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class TodoCommentControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataTodoCommentRepository: SpringDataTodoCommentRepository,
    private val springDataTodoRepository: SpringDataTodoRepository,
    private val springDataUserRepository: SpringDataUserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private lateinit var userId: UUID
    private lateinit var todoId: UUID
    private lateinit var token: String

    @BeforeEach
    fun cleanDatabase() {
        springDataTodoCommentRepository.deleteAll()
        springDataTodoRepository.deleteAll()
        springDataUserRepository.deleteAll()
        userId = UUID.randomUUID()
        todoId = UUID.randomUUID()
        springDataUserRepository.save(UserSpannerEntity(id = userId.toString(), username = "alice", passwordHash = "h"))
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
    fun `reject unauthenticated list comments request`() {
        mockMvc.perform(get("/api/todos/$todoId/comments"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `create, list, and delete comment for authenticated user`() {
        val createdBody = mockMvc.perform(
            post("/api/todos/$todoId/comments")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"First comment"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.content").value("First comment"))
            .andExpect(jsonPath("$.username").value("alice"))
            .andReturn().response.contentAsString

        val commentId = Regex(""""id":"([^"]+)"""").find(createdBody)?.groupValues?.get(1)

        mockMvc.perform(
            get("/api/todos/$todoId/comments")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        mockMvc.perform(
            delete("/api/todos/$todoId/comments/$commentId")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isNoContent)
    }
}
