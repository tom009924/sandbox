package com.example.todo.sideeffect.proxy

import com.example.todo.PubSubTestReceiver
import com.example.todo.ReceivedTodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.sideeffect.DbOperation
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.util.UUID

/**
 * サービスごとに分離された検証機構（Spanner専用プロキシ、WireMock、Pub/Sub）を
 * 統合し、テストコードに対して統一的なアサーション・残余検知インターフェースを提供する Facade。
 */
@Suppress("TooManyFunctions")
internal class CompositeSideEffectVerifier(
    private val spannerProxyClient: SpannerProxyClient,
    private val wireMockServer: WireMockServer,
    private val pubSubReceiver: PubSubTestReceiver,
) {
    private val verifiedHttpRequests = mutableListOf<LoggedRequest>()
    private val verifiedPubSubEvents = mutableListOf<ReceivedTodoEvent>()

    /**
     * テストケース開始時に呼び出し、Spanner プロキシおよび各クライアントの追跡を開始します。
     */
    fun startTracking() {
        spannerProxyClient.startTracking()
        verifiedHttpRequests.clear()
        verifiedPubSubEvents.clear()
    }

    /**
     * Spanner への更新操作（INSERT / UPDATE / DELETE / UPSERT）を検証・承認します。
     */
    fun verifyDatabase(operation: DbOperation, table: String, count: Int = 1) {
        spannerProxyClient.verifyDatabase(operation, table, count)
    }

    /**
     * Spanner の内部管理テーブル（例: idempotency_records）の更新を明示的に許容します。
     */
    fun allowDatabaseTable(table: String) {
        spannerProxyClient.allowTable(table)
    }

    /**
     * 外部 HTTP（WireMock）への更新系リクエストを検証・承認します。
     */
    fun verifyHttpRequest(
        method: RequestMethod,
        urlPattern: String,
        count: Int = 1,
    ): List<LoggedRequest> {
        val mutatingMethods = setOf(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE)
        require(method in mutatingMethods) {
            "Method $method is not a mutating HTTP request. Only ${mutatingMethods.joinToString()} are tracked."
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

    /**
     * Pub/Sub へのイベント発行副作用を検証・承認します。
     */
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

    /**
     * テスト終了時（@AfterEach）に実行し、各サービス（Spannerプロキシ、WireMock、PubSub）に
     * 未検証の副作用が残っていないかを一括検証します。
     */
    fun assertNoUnverifiedSideEffects() {
        // 1. Spanner プロキシの未アサーション検出
        spannerProxyClient.assertNoUnverifiedMutations()

        // 2. WireMock の未マッチ・未検証リクエスト検出
        wireMockServer.checkForUnmatchedRequests()
        val mutatingMethods = setOf(
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
        )
        val unverifiedHttp = wireMockServer.findAll(RequestPatternBuilder.allRequests())
            .filter { it.method in mutatingMethods && it !in verifiedHttpRequests }
        assertTrue(
            unverifiedHttp.isEmpty(),
            "Expected no external HTTP mutating requests, but found: " +
                unverifiedHttp.map { "${it.method} ${it.url}" },
        )

        // 3. Pub/Sub の未検証メッセージ残余検出
        if (pubSubReceiver.isAvailable) {
            val remaining = pubSubReceiver.pullEvents(
                maxMessages = MAX_PENDING_PUBSUB_CHECK_BATCH,
                maxWait = Duration.ofMillis(DEFAULT_ASSERT_NO_PUBSUB_TIMEOUT_MS),
            )
            assertTrue(
                remaining.isEmpty(),
                "Expected no Pub/Sub events, but received: ${remaining.map { it.event.eventType }}",
            )
        }
    }

    private companion object {
        const val DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS = 500L
        const val DEFAULT_ASSERT_NO_PUBSUB_TIMEOUT_MS = 200L
        const val MAX_PENDING_PUBSUB_CHECK_BATCH = 10
    }
}
