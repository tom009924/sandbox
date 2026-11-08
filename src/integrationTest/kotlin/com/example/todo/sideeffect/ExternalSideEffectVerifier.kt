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
 * 統合テストにおいて、データベース更新（Spanner）、非同期イベント発行（Pub/Sub）、
 * 外部API呼び出し（WireMock）などすべての外部接続の副作用（更新系操作）の
 * 検証（verify）漏れを自動検知し、強制的にテストを失敗させる検証器。
 *
 * 参照操作（SELECT / GET など）は副作用を持たないため追跡対象外となります。
 */
@Suppress("TooManyFunctions")
internal class ExternalSideEffectVerifier(
    private val wireMockServer: WireMockServer,
    private val pubSubReceiver: PubSubTestReceiver,
    initialAllowedDatabaseTables: Set<String> = emptySet(),
) {
    private val verifiedDatabaseMutations = mutableListOf<DatabaseMutation>()
    private val allowedDatabaseTables = initialAllowedDatabaseTables.map { it.lowercase() }.toMutableSet()
    private val verifiedHttpRequests = mutableListOf<LoggedRequest>()
    private val verifiedPubSubEvents = mutableListOf<ReceivedTodoEvent>()

    /**
     * テストケースの実行前に呼び出し、追跡状態を初期化して副作用の監視を開始します。
     * （@BeforeEach のセットアップ用DB初期化やトークン発行の完了後に呼び出します）
     */
    fun startTracking() {
        DatabaseSideEffectInspector.startTracking()
        verifiedDatabaseMutations.clear()
        verifiedHttpRequests.clear()
        verifiedPubSubEvents.clear()
    }

    /**
     * テストデータのセットアップ等、副作用追跡の対象外としたいブロックを実行します。
     */
    fun <T> withoutDatabaseTracking(block: () -> T): T =
        DatabaseSideEffectInspector.withoutTracking(block)

    /**
     * 特定のテーブルに対するデータベース更新（INSERT / UPDATE / DELETE）を検証・承認します。
     */
    fun verifyDatabase(
        operation: DbOperation,
        table: String,
        count: Int = 1,
    ) {
        val targetTable = table.lowercase()
        val unverified = DatabaseSideEffectInspector.getMutations().filter { it !in verifiedDatabaseMutations }
        val matching = unverified.filter { mutation ->
            val matchesOp = mutation.operation == operation ||
                (mutation.operation == DbOperation.UPSERT &&
                    (operation == DbOperation.INSERT || operation == DbOperation.UPDATE))
            matchesOp && mutation.table == targetTable
        }

        assertEquals(
            count,
            matching.size,
            "Expected $count $operation operations on table '$targetTable', but found ${matching.size}. " +
                "All unverified mutations: $unverified",
        )
        verifiedDatabaseMutations.addAll(matching.take(count))
    }

    /**
     * 操作種別を問わず、対象テーブルへの更新を承認します（例: 存在確認や状態アサート完了時）。
     */
    fun verifyDatabaseTable(table: String) {
        val targetTable = table.lowercase()
        val matching = DatabaseSideEffectInspector.getMutations()
            .filter { it.table == targetTable && it !in verifiedDatabaseMutations }
        verifiedDatabaseMutations.addAll(matching)
    }

    /**
     * 内部インフラ・管理用テーブル（例: idempotency_records 等のキャッシュや内部レコード）の
     * 副作用を明示的に許容します。
     */
    fun allowDatabaseMutations(table: String) {
        allowedDatabaseTables.add(table.lowercase())
    }

    /**
     * データベースに対する更新系の副作用が一切発生していないことを検証します。
     */
    fun assertNoDatabaseSideEffects() {
        val unverified = DatabaseSideEffectInspector.getMutations().filter {
            it !in verifiedDatabaseMutations && it.table !in allowedDatabaseTables
        }
        assertTrue(
            unverified.isEmpty(),
            "Expected no database mutations, but found: " +
                unverified.joinToString { "${it.operation} on ${it.table}" },
        )
    }

    /**
     * 外部 HTTP（WireMock）への副作用リクエスト（POST / PUT / PATCH / DELETE）を検証・承認します。
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
     * 外部 HTTP に対する更新系副作用が一切発生していないことを検証します。
     */
    fun assertNoHttpSideEffects() {
        val mutatingMethods = setOf(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE)
        val unverified = wireMockServer.findAll(RequestPatternBuilder.allRequests())
            .filter { it.method in mutatingMethods && it !in verifiedHttpRequests }
        assertTrue(
            unverified.isEmpty(),
            "Expected no external HTTP mutating requests, but found: ${unverified.map { "${it.method} ${it.url}" }}",
        )
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
     * テスト内で別途 pull / 検証された Pub/Sub イベントを検証済みとしてマークします。
     */
    fun markPubSubEventVerified(event: ReceivedTodoEvent) {
        verifiedPubSubEvents.add(event)
    }

    /**
     * Pub/Sub に対するイベント発行副作用が一切発生していないことを検証します。
     */
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

    /**
     * テスト終了時（@AfterEach）に実行し、すべての外部接続について
     * テストコードで検証（verify）されていない副作用が残っていないかを強制検証します。
     */
    fun assertNoUnverifiedSideEffects() {
        // 1. WireMock の未マッチリクエスト検証（スタブ定義されていないリクエストがあれば即失敗）
        wireMockServer.checkForUnmatchedRequests()

        // 2. WireMock の更新系リクエストの未検証チェック
        assertNoHttpSideEffects()

        // 3. Pub/Sub の未検証メッセージ残余チェック（キューに残っていれば即失敗）
        assertNoPubSubSideEffects()

        // 4. データベース（Spanner）の未検証更新チェック
        assertNoDatabaseSideEffects()
    }

    private companion object {
        const val DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS = 500L
        const val DEFAULT_ASSERT_NO_PUBSUB_TIMEOUT_MS = 200L
        const val MAX_PENDING_PUBSUB_CHECK_BATCH = 10
    }
}
