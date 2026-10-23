package com.example.todo.infrastructure.pubsub

import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PubSubTodoEventPublisherTest {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()

    @Test
    fun `event serializes to valid json with eventType and todo details`() {
        val eventId = UUID.randomUUID()
        val todoId = UUID.randomUUID()
        val timestamp = Instant.parse("2025-04-18T12:00:00Z")
        val userId = UUID.randomUUID()
        val todo = Todo(
            id = todoId,
            userId = userId,
            title = "Buy milk",
            completed = false,
            description = "Get fresh milk",
            dueDate = LocalDate.parse("2025-04-19"),
            priority = TodoPriority.HIGH,
            estimatedMinutes = 30,
        )
        val event = TodoEvent(
            eventId = eventId,
            eventType = TodoEventType.CREATED,
            timestamp = timestamp,
            todoId = todoId,
            todo = todo,
        )

        val json = objectMapper.writeValueAsString(event)
        val tree = objectMapper.readTree(json)

        assertEquals(eventId.toString(), tree.get("eventId").asText())
        assertEquals("CREATED", tree.get("eventType").asText())
        assertEquals(todoId.toString(), tree.get("todoId").asText())
        assertEquals("Buy milk", tree.get("todo").get("title").asText())
        assertEquals("HIGH", tree.get("todo").get("priority").asText())
        assertEquals(30, tree.get("todo").get("estimatedMinutes").asInt())
    }

    @Test
    fun `deleted event serializes with null todo`() {
        val event = TodoEvent(
            eventType = TodoEventType.DELETED,
            timestamp = Instant.now(),
            todoId = UUID.randomUUID(),
            todo = null,
        )

        val json = objectMapper.writeValueAsString(event)
        val tree = objectMapper.readTree(json)

        assertEquals("DELETED", tree.get("eventType").asText())
        assertNotNull(tree.get("todoId"))
        assertEquals(true, tree.get("todo").isNull)
    }

    @Test
    fun `publisher safely ignores events when credentials or emulator are not present`() {
        val properties = PubSubProperties(
            projectId = "test-project",
            topicId = "test-topic",
            emulatorHost = null,
        )
        val publisher = PubSubTodoEventPublisher(properties)

        // Should not throw even when standard publisher cannot connect without credentials
        publisher.publish(
            TodoEvent(
                eventType = TodoEventType.CREATED,
                timestamp = Instant.now(),
                todoId = UUID.randomUUID(),
                todo = Todo(userId = UUID.randomUUID(), title = "Test"),
            ),
        )
        publisher.close()
    }
}

