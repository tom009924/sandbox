package com.example.todo.sideeffect

import com.example.todo.PubSubTestReceiver
import com.example.todo.ReceivedTodoEvent
import com.example.todo.domain.event.TodoEventType
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.util.UUID

/**
 * 外部 HTTP（WireMock）および非同期イベント（Pub/Sub）の副作用検証を共通化するヘルパー。
 */
internal class ExternalEndpointSideEffectVerifier(
    private val wireMockServer: WireMockServer,
    private val pubSubReceiver: PubSubTestReceiver,
) {
    private val verifiedHttpRequests = mutableListOf<LoggedRequest>()
    private val verifiedPubSubEvents = mutableListOf<ReceivedTodoEvent>()

    fun startTracking() {
        verifiedHttpRequests.clear()
        verifiedPubSubEvents.clear()
    }

    fun verifyHttpRequest(
        method: RequestMethod,
        urlPattern: String,
        count: Int = 1,
    ): List<LoggedRequest> {
        require(method in MUTATING_HTTP_METHODS) {
            "Method $method is not a mutating HTTP request. Only ${MUTATING_HTTP_METHODS.joinToString()} are tracked."
        }

        val allRequests = wireMockServer.findAll(RequestPatternBuilder.allRequests())
        val unverified = allRequests.filter { it.method == method && it !in verifiedHttpRequests }
        val matching = unverified.filter { it.url.matches(Regex(urlPattern.replace("?", "\\?"))) }

        assertEquals(
            count,
            matching.size,
            "Expected $count $method requests matching '$urlPattern', but found ${matching.size}. " +
                "All unverified requests: ${unverified.map { "${it.method} ${it.url}" }}",
        )
        val verified = matching.take(count)
        verifiedHttpRequests.addAll(verified)
        return verified
    }

    fun assertNoHttpSideEffects() {
        val unverified = wireMockServer.findAll(RequestPatternBuilder.allRequests())
            .filter { it.method in MUTATING_HTTP_METHODS && it !in verifiedHttpRequests }
        assertTrue(
            unverified.isEmpty(),
            "Expected no external HTTP mutating requests, but found: ${unverified.map { "${it.method} ${it.url}" }}",
        )
    }

    fun verifyPubSub(
        expectedType: TodoEventType,
        expectedTodoId: UUID? = null,
        maxWait: Duration = Duration.ofMillis(DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS),
    ): ReceivedTodoEvent? {
        if (!pubSubReceiver.isAvailable) return null

        val pulled = pubSubReceiver.pullEvents(maxMessages = 1, maxWait = maxWait)
        assertEquals(1, pulled.size, "Expected 1 $expectedType event to be published to Pub/Sub")
        val received = pulled.first()

        assertEquals(expectedType.name, received.attributes["eventType"])
        assertEquals(expectedType, received.event.eventType)
        if (expectedTodoId != null) {
            assertEquals(expectedTodoId.toString(), received.attributes["todoId"])
            assertEquals(expectedTodoId, received.event.todoId)
        }
        verifiedPubSubEvents.add(received)
        return received
    }

    fun markPubSubEventVerified(event: ReceivedTodoEvent) {
        verifiedPubSubEvents.add(event)
    }

    fun assertNoPubSubSideEffects() {
        if (!pubSubReceiver.isAvailable) return
        val remaining = pubSubReceiver.pullEvents(
            maxMessages = MAX_PENDING_PUBSUB_CHECK_BATCH,
            maxWait = Duration.ofMillis(DEFAULT_ASSERT_NO_PUBSUB_TIMEOUT_MS),
        )
        assertTrue(
            remaining.isEmpty(),
            "Expected no Pub/Sub events, but received: ${remaining.map { it.event.eventType }}",
        )
    }

    fun assertNoUnverifiedEndpointSideEffects() {
        wireMockServer.checkForUnmatchedRequests()
        assertNoHttpSideEffects()
        assertNoPubSubSideEffects()
    }

    private companion object {
        val MUTATING_HTTP_METHODS = setOf(
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
        )
        const val DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS = 500L
        const val DEFAULT_ASSERT_NO_PUBSUB_TIMEOUT_MS = 200L
        const val MAX_PENDING_PUBSUB_CHECK_BATCH = 10
    }
}
