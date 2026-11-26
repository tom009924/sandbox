package com.example.integration.sideeffect

import com.example.integration.support.ContainerTestConfig
import com.fasterxml.jackson.databind.JsonNode
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
    val eventId: UUID? = null,
    val todoTitle: String? = null,
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

    @Volatile
    private var subscriptionEnsured: Boolean = false

    fun ensureSubscription() {
        if (subscriptionEnsured) {
            return
        }
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
        subscriptionEnsured = true
    }

    fun pullEvents(maxMessages: Int = DEFAULT_MAX_MESSAGES): List<ReceivedTodoEvent> {
        ensureSubscription()
        val rawResponse = executePull(maxMessages)
        val receivedMessages = rawResponse.jsonPath().getList<Map<String, Any>>("receivedMessages").orEmpty()
        if (receivedMessages.isEmpty()) {
            return emptyList()
        }

        acknowledgeMessages(receivedMessages)
        return receivedMessages.mapNotNull { parseReceivedMessage(it) }
    }

    fun drain() {
        ensureSubscription()
        while (pullEvents(DEFAULT_MAX_MESSAGES).isNotEmpty()) {
            // Drain remaining messages
        }
    }

    private fun executePull(maxMessages: Int) = run {
        val firstAttempt = given()
            .baseUri(ContainerTestConfig.pubSubBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"returnImmediately":true,"maxMessages":$maxMessages}""")
            .`when`()
            .post("/v1/$subscriptionPath:pull")

        val response = if (firstAttempt.statusCode() == HTTP_NOT_FOUND) {
            subscriptionEnsured = false
            ensureSubscription()
            given()
                .baseUri(ContainerTestConfig.pubSubBaseUrl)
                .contentType(ContentType.JSON)
                .body("""{"returnImmediately":true,"maxMessages":$maxMessages}""")
                .`when`()
                .post("/v1/$subscriptionPath:pull")
        } else {
            firstAttempt
        }

        check(response.statusCode() == HTTP_OK) {
            "Pub/Sub pull failed with HTTP ${response.statusCode()} on $subscriptionPath"
        }
        response
    }

    private fun acknowledgeMessages(receivedMessages: List<Map<String, Any>>) {
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
    }

    private fun parseReceivedMessage(item: Map<String, Any>): ReceivedTodoEvent? {
        val msg = item["message"] as? Map<*, *>
        val attributes = (msg?.get("attributes") as? Map<*, *>).orEmpty()
        val dataBase64 = msg?.get("data")?.toString().orEmpty()
        val rawJson = if (dataBase64.isNotEmpty()) {
            String(Base64.getDecoder().decode(dataBase64), StandardCharsets.UTF_8)
        } else {
            "{}"
        }
        val parsed = runCatching { objectMapper.readTree(rawJson) }.getOrNull()
        val eventType = attributes["eventType"]?.toString() ?: parsed.textOrNull("eventType")
        val todoIdRaw = attributes["todoId"]?.toString() ?: parsed.textOrNull("todoId")

        return if (msg != null && eventType != null && todoIdRaw != null) {
            val eventId = parsed.textOrNull("eventId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val todoTitle = parsed?.get("todo").textOrNull("title")
            ReceivedTodoEvent(
                eventType = eventType,
                todoId = UUID.fromString(todoIdRaw),
                rawJson = rawJson,
                eventId = eventId,
                todoTitle = todoTitle,
            )
        } else {
            null
        }
    }

    private fun JsonNode?.textOrNull(fieldName: String): String? =
        this?.get(fieldName)?.takeIf { !it.isNull }?.asText()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val DEFAULT_MAX_MESSAGES = 50
    }
}
