package com.example.todo.sideeffect

import com.example.todo.PubSubTestReceiver
import com.example.todo.ReceivedTodoEvent
import com.example.todo.domain.event.TodoEventType
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
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
    wireMockServer: WireMockServer,
    pubSubReceiver: PubSubTestReceiver,
    initialAllowedDatabaseTables: Set<String> = emptySet(),
) {
    private val verifiedDatabaseMutations: MutableSet<DatabaseMutation> =
        Collections.newSetFromMap(IdentityHashMap())
    private val allowedDatabaseTables = initialAllowedDatabaseTables.map { it.lowercase() }.toMutableSet()
    private val endpointVerifier = ExternalEndpointSideEffectVerifier(wireMockServer, pubSubReceiver)

    /**
     * テストケースの実行前に呼び出し、追跡状態を初期化して副作用の監視を開始します。
     * （@BeforeEach のセットアップ用DB初期化やトークン発行の完了後に呼び出します）
     */
    fun startTracking() {
        DatabaseSideEffectInspector.startTracking()
        verifiedDatabaseMutations.clear()
        endpointVerifier.startTracking()
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
                (
                    mutation.operation == DbOperation.UPSERT &&
                        (operation == DbOperation.INSERT || operation == DbOperation.UPDATE)
                    )
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
    ): List<LoggedRequest> = endpointVerifier.verifyHttpRequest(method, urlPattern, count)

    /**
     * 外部 HTTP に対する更新系副作用が一切発生していないことを検証します。
     */
    fun assertNoHttpSideEffects() = endpointVerifier.assertNoHttpSideEffects()

    /**
     * Pub/Sub へのイベント発行副作用を検証・承認します。
     */
    fun verifyPubSub(
        expectedType: TodoEventType,
        expectedTodoId: UUID? = null,
        maxWait: Duration = Duration.ofMillis(DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS),
    ): ReceivedTodoEvent? = endpointVerifier.verifyPubSub(expectedType, expectedTodoId, maxWait)

    /**
     * テスト内で別途 pull / 検証された Pub/Sub イベントを検証済みとしてマークします。
     */
    fun markPubSubEventVerified(event: ReceivedTodoEvent) {
        endpointVerifier.markPubSubEventVerified(event)
    }

    /**
     * Pub/Sub に対するイベント発行副作用が一切発生していないことを検証します。
     */
    fun assertNoPubSubSideEffects() = endpointVerifier.assertNoPubSubSideEffects()

    /**
     * テスト終了時（@AfterEach）に実行し、すべての外部接続について
     * テストコードで検証（verify）されていない副作用が残っていないかを強制検証します。
     */
    fun assertNoUnverifiedSideEffects() {
        endpointVerifier.assertNoUnverifiedEndpointSideEffects()
        assertNoDatabaseSideEffects()
    }

    private companion object {
        const val DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS = 500L
    }
}
