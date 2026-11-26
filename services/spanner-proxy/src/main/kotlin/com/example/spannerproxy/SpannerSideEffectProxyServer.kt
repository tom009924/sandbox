package com.example.spannerproxy

import com.google.spanner.v1.CommitRequest
import com.google.spanner.v1.ExecuteBatchDmlRequest
import com.google.spanner.v1.ExecuteSqlRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

internal enum class DbOperation {
    INSERT,
    UPDATE,
    UPSERT,
    DELETE,
}

internal data class RecordedMutation(
    val operation: DbOperation,
    val table: String,
)

/**
 * Pass-through gRPC proxy for Cloud Spanner Emulator with an HTTP admin API
 * for starting/stopping tracking and fetching recorded write operations.
 */
@Suppress("TooManyFunctions")
class SpannerSideEffectProxyServer(
    targetHost: String,
    targetPort: Int,
    grpcListenPort: Int,
    adminPort: Int,
) : AutoCloseable {

    private val recordedMutations = ConcurrentLinkedQueue<RecordedMutation>()

    @Volatile
    private var tracking: Boolean = false

    private val targetChannel: ManagedChannel = ManagedChannelBuilder
        .forAddress(targetHost, targetPort)
        .usePlaintext()
        .build()

    private val grpcServer: Server = ServerBuilder
        .forPort(grpcListenPort)
        .fallbackHandlerRegistry(ProxyHandlerRegistry())
        .build()

    private val httpServer: HttpServer = HttpServer.create(InetSocketAddress(adminPort), 0)

    init {
        configureHttpEndpoints()
        httpServer.executor = Executors.newCachedThreadPool()
    }

    fun start() {
        grpcServer.start()
        httpServer.start()
    }

    fun awaitTermination() {
        grpcServer.awaitTermination()
    }

    override fun close() {
        httpServer.stop(0)
        grpcServer.shutdownNow()
        targetChannel.shutdownNow()
    }

    private fun configureHttpEndpoints() {
        httpServer.createContext("/health") { exchange ->
            respondJson(exchange, HTTP_OK, """{"status":"UP"}""")
        }
        httpServer.createContext("/__admin/tracking/start") { exchange ->
            if (exchange.requestMethod != "POST") {
                respondJson(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"Method not allowed"}""")
                return@createContext
            }
            recordedMutations.clear()
            tracking = true
            respondJson(exchange, HTTP_OK, """{"tracking":true}""")
        }
        httpServer.createContext("/__admin/tracking/stop") { exchange ->
            if (exchange.requestMethod != "POST") {
                respondJson(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"Method not allowed"}""")
                return@createContext
            }
            tracking = false
            respondJson(exchange, HTTP_OK, """{"tracking":false}""")
        }
        httpServer.createContext("/__admin/mutations") { exchange ->
            when (exchange.requestMethod) {
                "GET" -> {
                    val items = recordedMutations.joinToString(",") {
                        """{"operation":"${it.operation.name}","table":"${it.table}"}"""
                    }
                    respondJson(exchange, HTTP_OK, "[$items]")
                }
                "DELETE" -> {
                    recordedMutations.clear()
                    respondJson(exchange, HTTP_OK, """{"cleared":true}""")
                }
                else -> respondJson(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"Method not allowed"}""")
            }
        }
    }

    private fun respondJson(exchange: HttpExchange, statusCode: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
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
                            runCatching {
                                inspectPayload(serverCall.methodDescriptor.fullMethodName, message)
                            }.onFailure { err ->
                                System.err.println("spanner-proxy inspectPayload error: ${err.message}")
                            }
                        }
                        clientCall.sendMessage(message)
                        serverCall.request(1)
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
            fullMethodName.endsWith("/ExecuteSql") -> inspectSql(ExecuteSqlRequest.parseFrom(messageBytes).sql)
            fullMethodName.endsWith("/ExecuteBatchDml") -> {
                ExecuteBatchDmlRequest.parseFrom(messageBytes).statementsList.forEach {
                    inspectSql(it.sql)
                }
            }
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

            recordedMutations.add(RecordedMutation(operation = op, table = table.lowercase()))
        }
    }

    private fun inspectSql(rawSql: String) {
        val cleanSql = stripComments(rawSql.trim())
        val firstWord = cleanSql.split(Regex("\\s+"), limit = 2).firstOrNull()?.uppercase()
        val operation = when (firstWord) {
            "INSERT" -> DbOperation.INSERT
            "UPDATE" -> DbOperation.UPDATE
            "DELETE" -> DbOperation.DELETE
            else -> null
        } ?: return

        recordedMutations.add(RecordedMutation(operation = operation, table = extractTable(operation, cleanSql)))
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
        private const val HTTP_OK = 200
        private const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}

fun main() {
    val targetHost = System.getenv("SPANNER_TARGET_HOST") ?: "spanner-emulator"
    val targetPort = System.getenv("SPANNER_TARGET_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val grpcPort = System.getenv("PROXY_GRPC_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val adminPort = System.getenv("PROXY_ADMIN_PORT")?.toIntOrNull() ?: DEFAULT_ADMIN_PORT

    val server = SpannerSideEffectProxyServer(
        targetHost = targetHost,
        targetPort = targetPort,
        grpcListenPort = grpcPort,
        adminPort = adminPort,
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
    server.awaitTermination()
}

private const val DEFAULT_PORT = 9010
private const val DEFAULT_ADMIN_PORT = 9030
