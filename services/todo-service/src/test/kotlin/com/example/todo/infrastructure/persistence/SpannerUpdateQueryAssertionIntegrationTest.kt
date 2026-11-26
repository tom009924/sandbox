package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.google.cloud.spanner.DatabaseAdminClient
import com.google.cloud.spanner.DatabaseClient
import com.google.cloud.spanner.DatabaseId
import com.google.cloud.spanner.InstanceConfigId
import com.google.cloud.spanner.InstanceId
import com.google.cloud.spanner.InstanceInfo
import com.google.cloud.spanner.Key
import com.google.cloud.spanner.Spanner
import com.google.cloud.spanner.SpannerOptions
import com.google.cloud.spanner.Statement
import com.google.cloud.spring.data.spanner.core.SpannerMutationFactoryImpl
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import com.google.cloud.spring.data.spanner.core.admin.SpannerSchemaUtils
import com.google.cloud.spring.data.spanner.core.convert.ConverterAwareMappingSpannerEntityProcessor
import com.google.cloud.spring.data.spanner.core.mapping.SpannerMappingContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.UUID

/**
 * Spanner Emulator への gRPC 通信を [SpannerQueryCaptureProxy] で傍受し、
 * 更新クエリ（DML SQL / パラメータ）および Mutation（テーブル・カラム・値）を
 * 完全一致でアサーションする検証用 Integration Test。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpannerUpdateQueryAssertionIntegrationTest {

    private lateinit var proxy: SpannerQueryCaptureProxy
    private lateinit var spanner: Spanner
    private lateinit var databaseClient: DatabaseClient
    private lateinit var spannerTemplate: SpannerTemplate
    private lateinit var todoRepositoryAdapter: TodoRepositoryAdapter
    private lateinit var idempotencyRepositoryAdapter: IdempotencyRecordRepositoryAdapter

    @BeforeAll
    fun setUpSpannerAndProxy() {
        assumeTrue(
            isEmulatorReachable(),
            "Spanner Emulator is not running on $EMULATOR_HOST:$EMULATOR_PORT; skipping query assertion test.",
        )

        proxy = SpannerQueryCaptureProxy(targetHost = EMULATOR_HOST, targetPort = EMULATOR_PORT)
        spanner = SpannerOptions.newBuilder()
            .setProjectId(PROJECT_ID)
            .setEmulatorHost("$EMULATOR_HOST:${proxy.grpcPort}")
            .setBuiltInMetricsEnabled(false)
            .setGrpcGcpOtelMetricsEnabled(false)
            .build()
            .service

        ensureDatabaseSchema(spanner)

        databaseClient = spanner.getDatabaseClient(DatabaseId.of(PROJECT_ID, INSTANCE_ID, DATABASE_ID))
        val mappingContext = SpannerMappingContext()
        val entityProcessor = ConverterAwareMappingSpannerEntityProcessor(mappingContext)
        val schemaUtils = SpannerSchemaUtils(mappingContext, entityProcessor, false)
        val mutationFactory = SpannerMutationFactoryImpl(entityProcessor, mappingContext, schemaUtils)
        spannerTemplate = SpannerTemplate(
            { databaseClient },
            mappingContext,
            entityProcessor,
            mutationFactory,
            schemaUtils,
        )

        todoRepositoryAdapter = TodoRepositoryAdapter(
            springDataTodoRepository = mock(SpringDataTodoRepository::class.java),
            spannerTemplate = spannerTemplate,
        )
        idempotencyRepositoryAdapter = IdempotencyRecordRepositoryAdapter(
            springDataRepository = mock(SpringDataIdempotencyRecordRepository::class.java),
            spannerTemplate = spannerTemplate,
            objectMapper = ObjectMapper(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (::spanner.isInitialized) {
            spanner.close()
        }
        if (::proxy.isInitialized) {
            proxy.close()
        }
    }

    @BeforeEach
    fun cleanTables() {
        proxy.stopTracking()
        databaseClient.readWriteTransaction().run { tx ->
            tx.executeUpdate(Statement.of("DELETE FROM todos WHERE TRUE"))
            tx.executeUpdate(Statement.of("DELETE FROM idempotency_records WHERE TRUE"))
        }
        proxy.clear()
    }

    @Test
    fun `DML DELETE query and bind parameters match exact expectation`() {
        val cutoff = Instant.parse("2026-01-01T00:00:00Z")

        proxy.startTracking()
        idempotencyRepositoryAdapter.deleteExpiredBefore(cutoff)
        proxy.stopTracking()

        val capturedDmls = proxy.recordedWriteDmls()
        assertEquals(1, capturedDmls.size)
        assertEquals(
            CapturedDml(
                sql = "DELETE FROM idempotency_records WHERE expires_at < @expiresAt",
                params = mapOf("expiresAt" to "2026-01-01T00:00:00Z"),
            ),
            capturedDmls.single(),
        )
    }

    @Test
    fun `DML UPDATE query matches exact SQL and parameters alongside DB state`() {
        val todoId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val entity = TodoSpannerEntity(
            id = todoId.toString(),
            userId = userId.toString(),
            title = "Initial Title",
            completed = false,
            priority = TodoPriority.MEDIUM.name,
        )
        spannerTemplate.insert(entity)

        val updateSql = "UPDATE todos SET title = @title, completed = @completed WHERE id = @id"
        proxy.startTracking()
        spannerTemplate.executeDmlStatement(
            Statement.newBuilder(updateSql)
                .bind("title").to("Updated Title")
                .bind("completed").to(true)
                .bind("id").to(todoId.toString())
                .build(),
        )
        proxy.stopTracking()

        // 1. クエリ文字列とバインドパラメータの完全一致アサーション
        val capturedDmls = proxy.recordedWriteDmls()
        assertEquals(1, capturedDmls.size)
        assertEquals(
            CapturedDml(
                sql = "UPDATE todos SET title = @title, completed = @completed WHERE id = @id",
                params = mapOf(
                    "title" to "Updated Title",
                    "completed" to "true",
                    "id" to todoId.toString(),
                ),
            ),
            capturedDmls.single(),
        )

        // 2. 参考: 状態ベースのアサーション（実際の DB レコード値）
        val reloaded = spannerTemplate.read(TodoSpannerEntity::class.java, Key.of(todoId.toString()))
        assertEquals("Updated Title", reloaded?.title)
        assertEquals(true, reloaded?.completed)
    }

    @Test
    fun `SpannerTemplate update Mutation matches exact table, columns, and row values`() {
        val todoId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        spannerTemplate.insert(
            TodoSpannerEntity(
                id = todoId.toString(),
                userId = userId.toString(),
                title = "Original",
                completed = false,
                priority = TodoPriority.LOW.name,
            ),
        )

        val updatedTodo = Todo(
            id = todoId,
            userId = userId,
            title = "Updated via Adapter",
            completed = true,
            description = "Updated description",
            dueDate = null,
            priority = TodoPriority.HIGH,
            estimatedMinutes = 30,
            tags = listOf("backend", "spanner"),
            projectId = null,
            assigneeId = null,
        )

        proxy.startTracking()
        todoRepositoryAdapter.save(updatedTodo)
        proxy.stopTracking()

        // Mutation のテーブル・カラム順・全カラム値を完全一致で検証
        val capturedMutations = proxy.recordedMutations()
        assertEquals(1, capturedMutations.size)
        val expectedColumns = listOf(
            "id", "user_id", "title", "completed", "description",
            "due_date", "priority", "estimated_minutes", "tags", "project_id", "assignee_id",
        )
        val expectedRow = listOf(
            todoId.toString(), userId.toString(), "Updated via Adapter", "true", "Updated description",
            "NULL", "HIGH", "30", "[backend, spanner]", "NULL", "NULL",
        )
        assertEquals(
            CapturedMutation(
                operation = MutationOperation.UPDATE,
                table = "todos",
                columns = expectedColumns,
                rows = listOf(expectedRow),
            ),
            capturedMutations.single(),
        )
    }

    private fun ensureDatabaseSchema(spanner: Spanner) {
        val instanceAdmin = spanner.instanceAdminClient
        val instanceId = InstanceId.of(PROJECT_ID, INSTANCE_ID)
        val hasInstance = runCatching { instanceAdmin.getInstance(INSTANCE_ID) }.isSuccess
        if (!hasInstance) {
            instanceAdmin.createInstance(
                InstanceInfo.newBuilder(instanceId)
                    .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                    .setNodeCount(1)
                    .build(),
            ).get()
        }

        val dbAdmin: DatabaseAdminClient = spanner.databaseAdminClient
        runCatching { dbAdmin.dropDatabase(INSTANCE_ID, DATABASE_ID) }
        dbAdmin.createDatabase(
            INSTANCE_ID,
            DATABASE_ID,
            listOf(
                """
                CREATE TABLE todos (
                    id STRING(36) NOT NULL,
                    user_id STRING(36) NOT NULL,
                    title STRING(100) NOT NULL,
                    completed BOOL NOT NULL,
                    description STRING(1000),
                    due_date DATE,
                    priority STRING(20) NOT NULL,
                    estimated_minutes INT64,
                    tags ARRAY<STRING(MAX)>,
                    project_id STRING(36),
                    assignee_id STRING(36)
                ) PRIMARY KEY (id)
                """.trimIndent(),
                """
                CREATE TABLE idempotency_records (
                    user_id STRING(36) NOT NULL,
                    key STRING(255) NOT NULL,
                    request_fingerprint STRING(64) NOT NULL,
                    response_snapshot STRING(MAX) NOT NULL,
                    expires_at TIMESTAMP NOT NULL
                ) PRIMARY KEY (user_id, key)
                """.trimIndent(),
            ),
        ).get()
    }

    private fun isEmulatorReachable(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(EMULATOR_HOST, EMULATOR_PORT), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val EMULATOR_HOST = "127.0.0.1"
        private const val EMULATOR_PORT = 9010
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val PROJECT_ID = "test-project"
        private const val INSTANCE_ID = "test-instance"
        private const val DATABASE_ID = "query-assertion-db"
    }
}
