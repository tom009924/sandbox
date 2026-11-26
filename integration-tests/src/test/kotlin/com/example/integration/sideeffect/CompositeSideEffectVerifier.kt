package com.example.integration.sideeffect

import com.example.integration.support.RecordedWireMockRequest
import com.example.integration.support.WireMockAdminClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Unified side-effect verifier across Spanner (`spanner-proxy`), WireMock, and Pub/Sub Emulator
 * for containerized integration tests.
 */
@Suppress("TooManyFunctions")
class CompositeSideEffectVerifier(
    private val pubSubClient: PubSubEmulatorClient = PubSubEmulatorClient(),
    initialAllowedTables: Set<String> = emptySet(),
) {
    private val allowedTables = initialAllowedTables.map { it.lowercase() }.toMutableSet()
    private val verifiedMutationIndices = Collections.synchronizedSet(mutableSetOf<Int>())
    private val verifiedHttpRequestIndices = Collections.synchronizedSet(mutableSetOf<Int>())
    private val bufferedPubSubEvents = mutableListOf<ReceivedTodoEvent>()
    private val verifiedPubSubEvents: MutableSet<ReceivedTodoEvent> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    fun startTracking() {
        verifiedMutationIndices.clear()
        verifiedHttpRequestIndices.clear()
        bufferedPubSubEvents.clear()
        verifiedPubSubEvents.clear()
        WireMockAdminClient.resetRequests()
        pubSubClient.drain()
        SpannerProxyAdminClient.startTracking()
    }

    fun stopTracking() {
        SpannerProxyAdminClient.stopTracking()
    }

    fun allowDatabaseTable(table: String) {
        allowedTables.add(table.lowercase())
    }

    fun verifyDatabase(
        operation: DbOperation,
        table: String,
        count: Int = 1,
        maxWait: Duration = Duration.ofSeconds(DEFAULT_DB_WAIT_SECONDS),
    ) {
        val targetTable = table.lowercase()
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (true) {
            val unverified = SpannerProxyAdminClient.fetchMutations()
                .withIndex()
                .filter { (idx, _) -> idx !in verifiedMutationIndices }
            val matching = unverified.filter { (_, mutation) ->
                val matchesOp = mutation.operation == operation ||
                    (
                        mutation.operation == DbOperation.UPSERT &&
                            (operation == DbOperation.INSERT || operation == DbOperation.UPDATE)
                        )
                matchesOp && mutation.table == targetTable
            }

            if (matching.size >= count || System.nanoTime() >= deadline) {
                assertEquals(
                    count,
                    matching.size,
                    "Expected $count $operation operations on table '$targetTable', but found ${matching.size}. " +
                        "All unverified mutations: ${unverified.map { it.value }}",
                )
                matching.take(count).forEach { (idx, _) -> verifiedMutationIndices.add(idx) }
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    fun verifyHttpRequest(
        method: String,
        urlPattern: String,
        count: Int = 1,
    ): List<RecordedWireMockRequest> {
        val regex = Regex(urlPattern)
        val unverified = WireMockAdminClient.fetchRequests()
            .withIndex()
            .filter { (idx, _) -> idx !in verifiedHttpRequestIndices }
        val matching = unverified.filter { (_, req) ->
            req.method.equals(method, ignoreCase = true) && regex.matches(req.url)
        }

        assertEquals(
            count,
            matching.size,
            "Expected $count $method requests matching '$urlPattern', but found ${matching.size}. " +
                "All unverified requests: ${unverified.map { it.value }}",
        )
        val selected = matching.take(count)
        selected.forEach { (idx, _) -> verifiedHttpRequestIndices.add(idx) }
        return selected.map { it.value }
    }

    fun verifyPubSub(
        expectedType: String,
        expectedTodoId: UUID? = null,
        maxWait: Duration = Duration.ofSeconds(DEFAULT_PUBSUB_WAIT_SECONDS),
    ): ReceivedTodoEvent {
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (true) {
            bufferedPubSubEvents.addAll(pubSubClient.pullEvents())
            val match = bufferedPubSubEvents.firstOrNull { event ->
                event !in verifiedPubSubEvents &&
                    event.eventType == expectedType &&
                    (expectedTodoId == null || event.todoId == expectedTodoId)
            }
            if (match != null) {
                verifiedPubSubEvents.add(match)
                return match
            }
            if (System.nanoTime() >= deadline) {
                val unverified = bufferedPubSubEvents.filter { it !in verifiedPubSubEvents }
                throw AssertionError(
                    "Expected Pub/Sub event $expectedType (todoId=$expectedTodoId), " +
                        "but none arrived within ${maxWait.toMillis()}ms. Unverified events: $unverified",
                )
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    fun assertNoUnverifiedSideEffects() {
        val unverifiedMutations = SpannerProxyAdminClient.fetchMutations()
            .withIndex()
            .filter { (idx, mutation) -> idx !in verifiedMutationIndices && mutation.table !in allowedTables }
            .map { it.value }
        assertTrue(
            unverifiedMutations.isEmpty(),
            "Unverified Spanner mutations detected (${unverifiedMutations.size}): " +
                unverifiedMutations.joinToString { "${it.operation} on ${it.table}" },
        )

        val unverifiedHttp = WireMockAdminClient.fetchRequests()
            .withIndex()
            .filter { (idx, _) -> idx !in verifiedHttpRequestIndices }
            .map { it.value }
        assertTrue(
            unverifiedHttp.isEmpty(),
            "Unverified WireMock HTTP requests detected (${unverifiedHttp.size}): " +
                unverifiedHttp.joinToString { "${it.method} ${it.url}" },
        )

        bufferedPubSubEvents.addAll(pubSubClient.pullEvents())
        val unverifiedPubSub = bufferedPubSubEvents.filter { it !in verifiedPubSubEvents }
        assertTrue(
            unverifiedPubSub.isEmpty(),
            "Unverified Pub/Sub events detected (${unverifiedPubSub.size}): " +
                unverifiedPubSub.joinToString { "${it.eventType}(${it.todoId})" },
        )
    }

    private companion object {
        const val DEFAULT_DB_WAIT_SECONDS = 3L
        const val DEFAULT_PUBSUB_WAIT_SECONDS = 3L
        const val POLL_INTERVAL_MS = 50L
    }
}
