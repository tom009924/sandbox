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
                .content("""{"title":"  Buy milk  "}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Buy milk"))
            .andExpect(jsonPath("$.completed").value(false))
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
                .content("""{"title":"Milk bought","completed":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Milk bought"))
            .andExpect(jsonPath("$.completed").value(true))

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
    fun `return not found for missing todo`() {
        mockMvc.perform(get("/api/todos/$MISSING_TODO_ID"))
            .andExpect(status().isNotFound)
    }

    private companion object {
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
