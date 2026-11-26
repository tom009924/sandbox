package com.example.integration.sideeffect

import com.example.integration.support.ContainerTestConfig
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class ReceivedTodoEvent(
    val eventType: String,
    val todoId: UUID,
    val rawJson: String,
)

/**
 * REST client for pulling and draining Todo events from the Pub/Sub Emulator container.
 */
class PubSubEmulatorClient(
    private val subscriptionId: String = "todo-events-sideeffect-test",
) {
    private val objectMapper = ObjectMapper()
    private val topicPath =
        "projects/${ContainerTestConfig.pubSubProjectId}/topics/${ContainerTestConfig.pubSubTopicId}"
    private val subscriptionPath =
        "projects/${ContainerTestConfig.pubSubProjectId}/subscriptions/$subscriptionId"

    fun ensureSubscription() {
        given()
            .baseUri(ContainerTestConfig.pubSubBaseUrl)
            .contentType(ContentType.JSON)
            .body("{}")
            .`when`()
            .put("/v1/$topicPath")

        val status = given()
            .baseUri(ContainerTestConfig.pubSubBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"topic":"$topicPath","ackDeadlineSeconds":10}""")
            .`when`()
            .put("/v1/$subscriptionPath")
            .statusCode()

        check(status in setOf(HTTP_OK, HTTP_CONFLICT)) {
            "Failed to ensure Pub/Sub subscription $subscriptionPath (HTTP $status)"
        }
    }

    fun pullEvents(maxMessages: Int = DEFAULT_MAX_MESSAGES): List<ReceivedTodoEvent> {
        ensureSubscription()
        val response = given()
            .baseUri(ContainerTestConfig.pubSubBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"returnImmediately":true,"maxMessages":$maxMessages}""")
            .`when`()
            .post("/v1/$subscriptionPath:pull")
            .then()
            .statusCode(HTTP_OK)
            .extract()

        val receivedMessages = response.jsonPath().getList<Map<String, Any>>("receivedMessages").orEmpty()
        if (receivedMessages.isEmpty()) {
            return emptyList()
        }

        val ackIds = receivedMessages.mapNotNull { it["ackId"]?.toString() }
        if (ackIds.isNotEmpty()) {
            val ackBody = objectMapper.writeValueAsString(mapOf("ackIds" to ackIds))
            given()
                .baseUri(ContainerTestConfig.pubSubBaseUrl)
                .contentType(ContentType.JSON)
                .body(ackBody)
                .`when`()
                .post("/v1/$subscriptionPath:acknowledge")
                .then()
                .statusCode(HTTP_OK)
        }

        return receivedMessages.mapNotNull { item ->
            val msg = item["message"] as? Map<*, *> ?: return@mapNotNull null
            val attributes = msg["attributes"] as? Map<*, *> ?: emptyMap<String, String>()
            val dataBase64 = msg["data"]?.toString().orEmpty()
            val rawJson = if (dataBase64.isNotEmpty()) {
                String(Base64.getDecoder().decode(dataBase64), StandardCharsets.UTF_8)
            } else {
                "{}"
            }
            val parsed = runCatching { objectMapper.readTree(rawJson) }.getOrNull()
            val eventType = attributes["eventType"]?.toString()
                ?: parsed?.get("eventType")?.asText()
                ?: return@mapNotNull null
            val todoIdRaw = attributes["todoId"]?.toString()
                ?: parsed?.get("todoId")?.asText()
                ?: return@mapNotNull null
            ReceivedTodoEvent(
                eventType = eventType,
                todoId = UUID.fromString(todoIdRaw),
                rawJson = rawJson,
            )
        }
    }

    fun drain() {
        ensureSubscription()
        while (pullEvents(DEFAULT_MAX_MESSAGES).isNotEmpty()) {
            // Drain remaining messages
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CONFLICT = 409
        const val DEFAULT_MAX_MESSAGES = 50
    }
}
