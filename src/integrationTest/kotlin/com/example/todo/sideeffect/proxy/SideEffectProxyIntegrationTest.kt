package com.example.todo.sideeffect.proxy

import com.example.todo.PubSubTestReceiver
import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.sideeffect.DbOperation
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.google.cloud.spanner.Mutation
import com.google.cloud.spanner.SpannerOptions
import com.google.cloud.spanner.Statement
import io.restassured.RestAssured
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SideEffectProxyIntegrationTest {

    @BeforeEach
    fun resetTracking() {
        val dbClient = spannerOptions.service.getDatabaseClient(
            com.google.cloud.spanner.DatabaseId.of("test-project", "test-instance", "test-db"),
        )
        dbClient.readWriteTransaction().run { transaction ->
            transaction.executeUpdate(Statement.of("DELETE FROM todos WHERE TRUE"))
            transaction.executeUpdate(Statement.of("DELETE FROM idempotency_records WHERE TRUE"))
        }
        wireMockServer.resetRequests()
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }
        verifier.startTracking()
    }

    @Test
    fun compositeVerifierDetectsUnverifiedSideEffectsAcrossComponents() {
        val dbClient = spannerOptions.service.getDatabaseClient(
            com.google.cloud.spanner.DatabaseId.of("test-project", "test-instance", "test-db"),
        )
        val todoId = UUID.randomUUID()

        // 1. Spanner 専用プロキシ経由で DB Mutation (INSERT) を発行
        dbClient.write(
            listOf(
                Mutation.newInsertBuilder("todos")
                    .set("id").to(todoId.toString())
                    .set("title").to("Proxy Test Todo")
                    .build(),
            ),
        )

        // 2. WireMock に HTTP POST を送信
        wireMockServer.stubFor(
            WireMock.post(WireMock.urlMatching("/api/notifications"))
                .willReturn(WireMock.aResponse().withStatus(HTTP_OK)),
        )
        RestAssured.given()
            .baseUri(wireMockServer.baseUrl())
            .body("{\"to\":\"user@example.com\"}")
            .post("/api/notifications")

        // 3. Pub/Sub にメッセージを発行
        if (pubSubReceiver.isAvailable) {
            publishPubSubEvent(todoId, TodoEventType.CREATED)
        }

        // 4. アサーション前：未アサーションの副作用が検知され、例外が発生することを確認
        val ex = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(ex.message!!.contains("todos") || ex.message!!.contains("/api/notifications"))

        // 5. アサーション実行
        verifier.verifyDatabase(DbOperation.INSERT, "todos")
        verifier.verifyHttpRequest(RequestMethod.POST, ".*/api/notifications")
        if (pubSubReceiver.isAvailable) {
            verifier.verifyPubSub(TodoEventType.CREATED, todoId)
        }

        // 6. すべてアサートされたので検証がパスすることを確認
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun proxyDetectsAndVerifiesUpdateOperations() {
        val dbClient = spannerOptions.service.getDatabaseClient(
            com.google.cloud.spanner.DatabaseId.of("test-project", "test-instance", "test-db"),
        )
        val todoId = UUID.randomUUID()

        // 事前データを挿入（追跡外でセットアップ）
        spannerProxy.stopTracking()
        dbClient.write(
            listOf(
                Mutation.newInsertBuilder("todos")
                    .set("id").to(todoId.toString())
                    .set("title").to("Original Title")
                    .build(),
            ),
        )
        spannerProxy.startTracking()

        // 1. UPDATE 操作を発行
        dbClient.write(
            listOf(
                Mutation.newUpdateBuilder("todos")
                    .set("id").to(todoId.toString())
                    .set("title").to("Updated Title")
                    .build(),
            ),
        )

        // 2. アサーション前：未検証の UPDATE が検知されて例外が発生することを確認
        val ex = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(
            ex.message!!.contains("UPDATE on todos"),
            "Error message should mention UPDATE on todos, but was: ${ex.message}",
        )

        // 3. UPDATE を明示的に検証
        verifier.verifyDatabase(DbOperation.UPDATE, "todos")

        // 4. 検証がパスすることを確認
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun spannerProxyIgnoresSelectQueries() {
        val dbClient = spannerOptions.service.getDatabaseClient(
            com.google.cloud.spanner.DatabaseId.of("test-project", "test-instance", "test-db"),
        )

        // SELECT クエリ（副作用なし）を発行
        val resultSet = dbClient.singleUse().executeQuery(Statement.of("SELECT 1 AS num"))
        while (resultSet.next()) {
            // read
        }

        // SELECT は副作用として検知されないためパスする
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun spannerProxyAllowsSpecifiedDatabaseTables() {
        val dbClient = spannerOptions.service.getDatabaseClient(
            com.google.cloud.spanner.DatabaseId.of("test-project", "test-instance", "test-db"),
        )

        // idempotency_records（内部管理テーブル）の更新
        dbClient.write(
            listOf(
                Mutation.newInsertBuilder("idempotency_records")
                    .set("key").to("idem-key-" + UUID.randomUUID())
                    .build(),
            ),
        )

        // initialAllowedTables に設定されているため未アサーションでもパスする
        verifier.assertNoUnverifiedSideEffects()
    }

    private fun publishPubSubEvent(todoId: UUID, eventType: TodoEventType) {
        val publisher = com.google.cloud.pubsub.v1.Publisher.newBuilder("projects/test-project/topics/todo-events")
            .setChannelProvider(
                com.google.api.gax.grpc.InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:8085")
                    .setChannelConfigurator { it.usePlaintext() }
                    .build(),
            )
            .setCredentialsProvider(com.google.api.gax.core.NoCredentialsProvider.create())
            .build()
        try {
            val event = TodoEvent(
                eventId = UUID.randomUUID(),
                eventType = eventType,
                todoId = todoId,
                timestamp = Instant.now(),
            )
            val json = tools.jackson.databind.ObjectMapper().writeValueAsString(event)
            publisher.publish(
                com.google.pubsub.v1.PubsubMessage.newBuilder()
                    .putAttributes("eventType", eventType.name)
                    .putAttributes("todoId", todoId.toString())
                    .setData(com.google.protobuf.ByteString.copyFromUtf8(json))
                    .build(),
            ).get()
        } finally {
            publisher.shutdown()
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private lateinit var spannerProxy: SpannerSideEffectProxy
        private lateinit var wireMockServer: WireMockServer
        private lateinit var pubSubReceiver: PubSubTestReceiver
        private lateinit var verifier: CompositeSideEffectVerifier
        private lateinit var spannerOptions: SpannerOptions

        @JvmStatic
        @BeforeAll
        fun setupAll() {
            // 1. Spanner 専用プロキシの起動（テスト内で起動）
            spannerProxy = SpannerSideEffectProxy(
                targetHost = "localhost",
                targetPort = 9010,
                initialAllowedTables = setOf("idempotency_records"),
            )

            // 2. WireMock サーバーの起動（外部 HTTP 用）
            wireMockServer = WireMockServer(wireMockConfig().dynamicPort())
            wireMockServer.start()

            // 3. Pub/Sub レシーバの初期化
            pubSubReceiver = PubSubTestReceiver(
                projectId = "test-project",
                topicId = "todo-events",
                subscriptionId = "todo-events-sideeffect-test",
                emulatorHost = "localhost:8085",
            )

            // 4. CompositeSideEffectVerifier で束ねる
            verifier = CompositeSideEffectVerifier(
                spannerProxy = spannerProxy,
                wireMockServer = wireMockServer,
                pubSubReceiver = pubSubReceiver,
            )

            // Spanner クライアントをプロキシに向ける
            spannerOptions = SpannerOptions.newBuilder()
                .setProjectId("test-project")
                .setEmulatorHost("localhost:${spannerProxy.grpcPort}")
                .build()

            initSpannerSchema()
        }

        private fun initSpannerSchema() {
            val spanner = spannerOptions.service
            try {
                spanner.instanceAdminClient.createInstance(
                    com.google.cloud.spanner.InstanceInfo.newBuilder(
                        com.google.cloud.spanner.InstanceId.of("test-project", "test-instance"),
                    ).build(),
                ).get()
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                // already exists
            }

            try {
                spanner.databaseAdminClient.createDatabase(
                    "test-instance",
                    "test-db",
                    listOf(
                        "CREATE TABLE todos (id STRING(36) NOT NULL, title STRING(255)) PRIMARY KEY(id)",
                        "CREATE TABLE idempotency_records (key STRING(100) NOT NULL) PRIMARY KEY(key)",
                    ),
                ).get()
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                // already exists
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDownAll() {
            spannerProxy.close()
            wireMockServer.stop()
            spannerOptions.service.close()
            if (pubSubReceiver.isAvailable) {
                pubSubReceiver.close()
            }
        }
    }
}
