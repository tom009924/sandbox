package com.example.todo.sideeffect.proxy

import com.example.todo.PubSubTestReceiver
import com.example.todo.ReceivedTodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.sideeffect.DbOperation
import com.example.todo.sideeffect.ExternalEndpointSideEffectVerifier
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import java.time.Duration
import java.util.UUID

/**
 * サービスごとに分離された検証機構（Spanner専用プロキシ、WireMock、Pub/Sub）を統合し、
 * 統合テストコードに対して統一的なアサーション・残余検知インターフェースを提供する Facade。
 */
@Suppress("TooManyFunctions")
internal class CompositeSideEffectVerifier(
    private val spannerProxy: SpannerSideEffectProxy,
    wireMockServer: WireMockServer,
    pubSubReceiver: PubSubTestReceiver,
) {
    private val endpointVerifier = ExternalEndpointSideEffectVerifier(wireMockServer, pubSubReceiver)

    /**
     * テストケース開始時に呼び出し、Spanner プロキシおよび各クライアントの追跡を開始します。
     */
    fun startTracking() {
        spannerProxy.startTracking()
        endpointVerifier.startTracking()
    }

    /**
     * Spanner への更新操作（INSERT / UPDATE / DELETE / UPSERT）を検証・承認します。
     */
    fun verifyDatabase(operation: DbOperation, table: String, count: Int = 1) {
        spannerProxy.verifyDatabase(operation, table, count)
    }

    /**
     * Spanner の内部管理テーブル（例: idempotency_records）の更新を明示的に許容します。
     */
    fun allowDatabaseTable(table: String) {
        spannerProxy.allowTable(table)
    }

    /**
     * 外部 HTTP（WireMock）への更新系リクエストを検証・承認します。
     */
    fun verifyHttpRequest(
        method: RequestMethod,
        urlPattern: String,
        count: Int = 1,
    ): List<LoggedRequest> = endpointVerifier.verifyHttpRequest(method, urlPattern, count)

    /**
     * Pub/Sub へのイベント発行副作用を検証・承認します。
     */
    fun verifyPubSub(
        expectedType: TodoEventType,
        expectedTodoId: UUID? = null,
        maxWait: Duration = Duration.ofMillis(DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS),
    ): ReceivedTodoEvent? = endpointVerifier.verifyPubSub(expectedType, expectedTodoId, maxWait)

    /**
     * テスト終了時（@AfterEach）に実行し、各サービス（Spannerプロキシ、WireMock、PubSub）に
     * 未検証の副作用が残っていないかを一括検証します。
     */
    fun assertNoUnverifiedSideEffects() {
        spannerProxy.assertNoUnverifiedMutations()
        endpointVerifier.assertNoUnverifiedEndpointSideEffects()
    }

    private companion object {
        const val DEFAULT_VERIFY_PUBSUB_TIMEOUT_MS = 500L
    }
}
