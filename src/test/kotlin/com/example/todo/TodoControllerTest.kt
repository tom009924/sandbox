package com.example.todo

import com.example.todo.infrastructure.persistence.SpringDataTodoRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataTodoRepository.deleteAll()
    }

    @Test
    fun `create list update and delete todo`() {
        val created = mockMvc.perform(
            post("/api/todos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
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
            .andReturn()
            .response
            .contentAsString
        val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1])

        mockMvc.perform(get("/api/todos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(id.toString()))

        mockMvc.perform(
            put("/api/todos/$id")
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

        mockMvc.perform(delete("/api/todos/$id"))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        mockMvc.perform(get("/api/todos/$id"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `reject blank title`() {
        mockMvc.perform(
            post("/api/todos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"  "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `reject negative estimated minutes`() {
        mockMvc.perform(
            post("/api/todos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Buy milk","estimatedMinutes":-1}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `use defaults for omitted optional metadata`() {
        mockMvc.perform(
            post("/api/todos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Buy milk"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.description").doesNotExist())
            .andExpect(jsonPath("$.dueDate").doesNotExist())
            .andExpect(jsonPath("$.priority").value("MEDIUM"))
    }

    @Test
    fun `return not found for missing todo`() {
        mockMvc.perform(get("/api/todos/$MISSING_TODO_ID"))
            .andExpect(status().isNotFound)
    }

    private companion object {
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
