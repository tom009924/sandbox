package com.example.todo.sideeffect.proxy

import com.example.todo.sideeffect.DatabaseMutation
import com.example.todo.sideeffect.DbOperation
import com.google.spanner.v1.CommitRequest
import com.google.spanner.v1.ExecuteSqlRequest
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.HandlerRegistry
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerMethodDefinition
import io.grpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Cloud Spanner への通信をエミュレータへ透過転送（Pass-through）しつつ、
 * データベースへの副作用（INSERT / UPDATE / DELETE / UPSERT）を直接捕捉・検証する軽量プロキシ。
 *
 * アプリケーションが別プロセスや Docker コンテナで動作している場合でも、
 * 本プロキシのポート（grpcPort）を接続先として指定することで副作用を完全に検知できます。
 */
@Suppress("TooManyFunctions")
internal class SpannerSideEffectProxy(
    targetHost: String = "localhost",
    targetPort: Int = 9010,
    listenPort: Int = 0,
    initialAllowedTables: Set<String> = emptySet(),
) : AutoCloseable {

    private val recordedMutations = ConcurrentLinkedQueue<DatabaseMutation>()
    private val verifiedMutations = ConcurrentLinkedQueue<DatabaseMutation>()
    private val allowedTables = ConcurrentLinkedQueue(initialAllowedTables.map { it.lowercase() })

    @Volatile
    private var tracking: Boolean = false

    private val targetChannel: ManagedChannel = ManagedChannelBuilder
        .forAddress(targetHost, targetPort)
        .usePlaintext()
        .build()

    private val grpcServer: Server = ServerBuilder
        .forPort(listenPort)
        .fallbackHandlerRegistry(ProxyHandlerRegistry())
        .build()

    val grpcPort: Int

    init {
        grpcServer.start()
        grpcPort = grpcServer.port
    }

    fun startTracking() {
        recordedMutations.clear()
        verifiedMutations.clear()
        tracking = true
    }

    fun stopTracking() {
        tracking = false
    }

    fun allowTable(table: String) {
        allowedTables.add(table.lowercase())
    }

    /**
     * INSERT, UPDATE, DELETE, UPSERT の副作用を検証・承認します。
     */
    fun verifyDatabase(operation: DbOperation, table: String, count: Int = 1) {
        val targetTable = table.lowercase()
        val unverified = recordedMutations.filter { it !in verifiedMutations }
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
        verifiedMutations.addAll(matching.take(count))
    }

    /**
     * 未アサーションの副作用（UPDATE や INSERT の検証漏れ）が残っていないかを強制検証します。
     */
    fun assertNoUnverifiedMutations() {
        val unverified = recordedMutations.filter {
            it !in verifiedMutations && it.table !in allowedTables
        }
        assertTrue(
            unverified.isEmpty(),
            "Unverified Spanner database mutations detected (${unverified.size}): " +
                unverified.joinToString { "${it.operation} on ${it.table}" },
        )
    }

    override fun close() {
        grpcServer.shutdownNow()
        targetChannel.shutdownNow()
        grpcServer.awaitTermination(WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        targetChannel.awaitTermination(WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private inner class ProxyHandlerRegistry : HandlerRegistry() {
        private val byteMarshaller = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
            override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
        }

        override fun lookupMethod(methodName: String, authority: String?): ServerMethodDefinition<*, *>? {
            val methodDescriptor = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
                .setType(MethodDescriptor.MethodType.UNKNOWN)
                .setFullMethodName(methodName)
                .setRequestMarshaller(byteMarshaller)
                .setResponseMarshaller(byteMarshaller)
                .build()

            val handler = ServerCallHandler<ByteArray, ByteArray> { serverCall, headers ->
                val clientCall = targetChannel.newCall(methodDescriptor, CallOptions.DEFAULT)

                val clientListener = object : ClientCall.Listener<ByteArray>() {
                    override fun onHeaders(responseHeaders: Metadata) = serverCall.sendHeaders(responseHeaders)
                    override fun onMessage(message: ByteArray) = serverCall.sendMessage(message)
                    override fun onClose(status: Status, trailers: Metadata) = serverCall.close(status, trailers)
                    override fun onReady() = serverCall.request(1)
                }

                clientCall.start(clientListener, headers)
                serverCall.request(1)

                object : ServerCall.Listener<ByteArray>() {
                    override fun onMessage(message: ByteArray) {
                        if (tracking) {
                            try {
                                inspectPayload(serverCall.methodDescriptor.fullMethodName, message)
                            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Throwable) {
                                // 検査失敗時も透過通信は妨げない
                            }
                        }
                        clientCall.sendMessage(message)
                    }

                    override fun onHalfClose() = clientCall.halfClose()
                    override fun onCancel() = clientCall.cancel("Server call cancelled", null)
                    override fun onReady() = clientCall.request(1)
                }
            }
            return ServerMethodDefinition.create(methodDescriptor, handler)
        }
    }

    private fun inspectPayload(fullMethodName: String, messageBytes: ByteArray) {
        when {
            fullMethodName.endsWith("/Commit") -> inspectCommit(messageBytes)
            fullMethodName.endsWith("/ExecuteSql") -> inspectExecuteSql(messageBytes)
        }
    }

    private fun inspectCommit(messageBytes: ByteArray) {
        val request = CommitRequest.parseFrom(messageBytes)
        request.mutationsList.forEach { mutation ->
            val (op, table) = when {
                mutation.hasInsert() -> DbOperation.INSERT to mutation.insert.table
                mutation.hasUpdate() -> DbOperation.UPDATE to mutation.update.table
                mutation.hasInsertOrUpdate() -> DbOperation.UPSERT to mutation.insertOrUpdate.table
                mutation.hasReplace() -> DbOperation.UPDATE to mutation.replace.table
                mutation.hasDelete() -> DbOperation.DELETE to mutation.delete.table
                else -> null
            } ?: return@forEach

            recordedMutations.add(
                DatabaseMutation(operation = op, table = table.lowercase(), sql = "Spanner Mutation: $op on $table"),
            )
        }
    }

    private fun inspectExecuteSql(messageBytes: ByteArray) {
        val request = ExecuteSqlRequest.parseFrom(messageBytes)
        val cleanSql = stripComments(request.sql.trim())
        val firstWord = cleanSql.split(Regex("\\s+"), limit = 2).firstOrNull()?.uppercase()

        val operation = when (firstWord) {
            "INSERT" -> DbOperation.INSERT
            "UPDATE" -> DbOperation.UPDATE
            "DELETE" -> DbOperation.DELETE
            else -> null
        } ?: return

        val table = extractTable(operation, cleanSql)
        recordedMutations.add(
            DatabaseMutation(operation = operation, table = table.lowercase(), sql = cleanSql),
        )
    }

    private fun stripComments(sql: String): String {
        var result = sql
        while (result.startsWith("/*")) {
            val endIndex = result.indexOf("*/")
            result = if (endIndex != -1) result.substring(endIndex + 2).trimStart() else break
        }
        return result
    }

    private fun extractTable(operation: DbOperation, sql: String): String {
        val tokens = sql.split(Regex("\\s+")).map { it.trim('`', '"', '\'', ';', '(', ')') }
        val target = when (operation) {
            DbOperation.INSERT -> {
                val intoIndex = tokens.indexOfFirst { it.equals("into", ignoreCase = true) }
                if (intoIndex != -1 && intoIndex + 1 < tokens.size) tokens[intoIndex + 1] else "unknown"
            }
            DbOperation.UPDATE, DbOperation.UPSERT -> {
                val updateIndex = tokens.indexOfFirst { it.equals("update", ignoreCase = true) }
                if (updateIndex != -1 && updateIndex + 1 < tokens.size) tokens[updateIndex + 1] else "unknown"
            }
            DbOperation.DELETE -> {
                val fromIndex = tokens.indexOfFirst { it.equals("from", ignoreCase = true) }
                if (fromIndex != -1 && fromIndex + 1 < tokens.size) tokens[fromIndex + 1] else "unknown"
            }
        }
        return target.lowercase()
    }

    companion object {
        private const val WAIT_TIMEOUT_SEC = 2L
    }
}
