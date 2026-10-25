package com.example.todo

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.infrastructure.persistence.SpringDataIdempotencyRecordRepository
import com.example.todo.infrastructure.persistence.SpringDataTodoRepository
import com.example.todo.infrastructure.persistence.SpringDataUserRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class TodoControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val springDataTodoRepository: SpringDataTodoRepository,
    private val springDataIdempotencyRecordRepository: SpringDataIdempotencyRecordRepository,
    private val springDataUserRepository: SpringDataUserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val testUserId: UUID = UUID.randomUUID()
    private lateinit var token: String

    @BeforeEach
    fun cleanDatabase() {
        springDataTodoRepository.deleteAll()
        springDataIdempotencyRecordRepository.deleteAll()
        springDataUserRepository.deleteAll()
        token = jwtTokenProvider.generateToken(AuthenticatedUser(testUserId, "testuser"))
    }

    @Test
    fun `reject unauthenticated request`() {
        mockMvc.perform(get("/api/todos"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `create and list todo`() {
        val created = mockMvc.perform(
            createRequest(
                """
                    {
                      "title":"  Buy milk  ",
                      "description":"Buy before the weekend",
                      "dueDate":"2025-04-18",
                      "priority":"HIGH",
                      "estimatedMinutes":30
                    }
                    """.trimIndent(),
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Buy milk"))
            .andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.description").value("Buy before the weekend"))
            .andExpect(jsonPath("$.dueDate").value("2025-04-18"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andExpect(jsonPath("$.estimatedMinutes").value(30))
            .andExpect(jsonPath("$.userId").value(testUserId.toString()))
            .andReturn()
            .response
            .contentAsString
        val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1])

        mockMvc.perform(get("/api/todos").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(id.toString()))
            .andExpect(jsonPath("$[0].userId").value(testUserId.toString()))
    }

    @Test
    fun `update and delete todo`() {
        val created = mockMvc.perform(
            createRequest(
                """
                    {
                      "title":"Buy milk",
                      "description":"Buy before the weekend",
                      "dueDate":"2025-04-18",
                      "priority":"HIGH",
                      "estimatedMinutes":30
                    }
                    """.trimIndent(),
            ),
        ).andReturn().response.contentAsString
        val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1])

        mockMvc.perform(
            put("/api/todos/$id")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title":"Milk bought",
                      "completed":true,
                      "description":"Purchased",
                      "dueDate":"2025-04-19",
                      "priority":"LOW",
                      "estimatedMinutes":45
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Milk bought"))
            .andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.description").value("Purchased"))
            .andExpect(jsonPath("$.dueDate").value("2025-04-19"))
            .andExpect(jsonPath("$.priority").value("LOW"))
            .andExpect(jsonPath("$.estimatedMinutes").value(45))

        mockMvc.perform(delete("/api/todos/$id").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        mockMvc.perform(get("/api/todos/$id").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `patch partially updates todo`() {
        val created = mockMvc.perform(
            createRequest("""{"title":"Buy milk","description":"Grocery","priority":"LOW"}"""),
        ).andReturn().response.contentAsString
        val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1])

        mockMvc.perform(
            patch("/api/todos/$id")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"completed":true,"priority":"HIGH"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Buy milk"))
            .andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.description").value("Grocery"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
    }

    @Test
    fun `filter todos by completed and priority`() {
        mockMvc.perform(createRequest("""{"title":"Task 1","priority":"LOW"}"""))
            .andExpect(status().isCreated)
        val created2 = mockMvc.perform(createRequest("""{"title":"Task 2","priority":"HIGH"}"""))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val id2 = UUID.fromString(Regex(""""id":"([^"]+)"""").find(created2)!!.groupValues[1])
        mockMvc.perform(
            patch("/api/todos/$id2")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"completed":true}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/todos?completed=true&priority=HIGH").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Task 2"))

        mockMvc.perform(get("/api/todos?completed=false").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Task 1"))
    }

    @Test
    fun `reject blank title`() {
        mockMvc.perform(
            createRequest("""{"title":"  "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `reject negative estimated minutes`() {
        mockMvc.perform(
            createRequest("""{"title":"Buy milk","estimatedMinutes":-1}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `use defaults for omitted optional metadata`() {
        mockMvc.perform(
            createRequest("""{"title":"Buy milk"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.description").doesNotExist())
            .andExpect(jsonPath("$.dueDate").doesNotExist())
            .andExpect(jsonPath("$.priority").value("MEDIUM"))
    }

    @Test
    fun `require idempotency key for create`() {
        mockMvc.perform(
            post("/api/todos")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Buy milk"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `reject blank and overlong idempotency keys`() {
        listOf(" ", "k".repeat(256)).forEach { idempotencyKey ->
            mockMvc.perform(createRequest("""{"title":"Buy milk"}""", idempotencyKey))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.message").value("Invalid request parameters"))
        }
    }

    @Test
    fun `return not found for missing todo`() {
        mockMvc.perform(get("/api/todos/$MISSING_TODO_ID").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    private fun createRequest(
        body: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = post("/api/todos")
        .header("Authorization", "Bearer $token")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private companion object {
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
