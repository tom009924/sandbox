package com.example.todo.infrastructure.persistence

import com.google.protobuf.Value
import com.google.spanner.v1.CommitRequest
import com.google.spanner.v1.ExecuteBatchDmlRequest
import com.google.spanner.v1.ExecuteSqlRequest
import com.google.spanner.v1.Mutation
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

internal enum class MutationOperation {
    INSERT,
    UPDATE,
    INSERT_OR_UPDATE,
    REPLACE,
    DELETE,
}

internal data class CapturedDml(
    val sql: String,
    val params: Map<String, String>,
)

internal data class CapturedMutation(
    val operation: MutationOperation,
    val table: String,
    val columns: List<String>,
    val rows: List<List<String>>,
)

/**
 * Pass-through gRPC proxy for Cloud Spanner Emulator that captures exact DML statements
 * and Commit mutations sent over the wire.
 */
@Suppress("TooManyFunctions")
internal class SpannerQueryCaptureProxy(
    targetHost: String = "127.0.0.1",
    targetPort: Int = DEFAULT_SPANNER_EMULATOR_PORT,
    listenPort: Int = 0,
) : AutoCloseable {

    private val dmls = ConcurrentLinkedQueue<CapturedDml>()
    private val mutations = ConcurrentLinkedQueue<CapturedMutation>()

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
        clear()
        tracking = true
    }

    fun stopTracking() {
        tracking = false
    }

    fun clear() {
        dmls.clear()
        mutations.clear()
    }

    fun recordedWriteDmls(): List<CapturedDml> = dmls.filter { isWriteDml(it.sql) }

    fun recordedMutations(): List<CapturedMutation> = mutations.toList()

    override fun close() {
        grpcServer.shutdownNow()
        targetChannel.shutdownNow()
        grpcServer.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        targetChannel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
            fullMethodName.endsWith("/ExecuteSql") -> {
                val request = ExecuteSqlRequest.parseFrom(messageBytes)
                dmls.add(
                    CapturedDml(
                        sql = request.sql,
                        params = request.params.fieldsMap.mapValues { (_, v) -> formatValue(v) },
                    ),
                )
            }
            fullMethodName.endsWith("/ExecuteBatchDml") -> {
                val request = ExecuteBatchDmlRequest.parseFrom(messageBytes)
                request.statementsList.forEach { stmt ->
                    dmls.add(
                        CapturedDml(
                            sql = stmt.sql,
                            params = stmt.params.fieldsMap.mapValues { (_, v) -> formatValue(v) },
                        ),
                    )
                }
            }
            fullMethodName.endsWith("/Commit") -> {
                val request = CommitRequest.parseFrom(messageBytes)
                request.mutationsList.forEach { mutation ->
                    toCapturedMutation(mutation)?.let(mutations::add)
                }
            }
        }
    }

    private fun toCapturedMutation(mutation: Mutation): CapturedMutation? = when {
        mutation.hasInsert() -> writeMutation(MutationOperation.INSERT, mutation.insert)
        mutation.hasUpdate() -> writeMutation(MutationOperation.UPDATE, mutation.update)
        mutation.hasInsertOrUpdate() -> writeMutation(MutationOperation.INSERT_OR_UPDATE, mutation.insertOrUpdate)
        mutation.hasReplace() -> writeMutation(MutationOperation.REPLACE, mutation.replace)
        mutation.hasDelete() -> CapturedMutation(
            operation = MutationOperation.DELETE,
            table = mutation.delete.table,
            columns = emptyList(),
            rows = mutation.delete.keySet.keysList.map { row -> row.valuesList.map(::formatValue) },
        )
        else -> null
    }

    private fun writeMutation(operation: MutationOperation, write: Mutation.Write): CapturedMutation =
        CapturedMutation(
            operation = operation,
            table = write.table,
            columns = write.columnsList.toList(),
            rows = write.valuesList.map { row -> row.valuesList.map(::formatValue) },
        )

    private fun formatValue(value: Value): String = when (value.kindCase) {
        Value.KindCase.NULL_VALUE -> "NULL"
        Value.KindCase.BOOL_VALUE -> value.boolValue.toString()
        Value.KindCase.NUMBER_VALUE -> value.numberValue.toString()
        Value.KindCase.STRING_VALUE -> value.stringValue
        Value.KindCase.LIST_VALUE -> value.listValue.valuesList.joinToString(
            prefix = "[",
            postfix = "]",
            transform = ::formatValue,
        )
        Value.KindCase.STRUCT_VALUE -> value.structValue.fieldsMap.entries.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (k, v) -> "$k=${formatValue(v)}" }
        else -> ""
    }

    private fun isWriteDml(sql: String): Boolean {
        val normalized = sql.trimStart()
        val firstWord = normalized.split(Regex("\\s+"), limit = 2).firstOrNull()?.uppercase()
        return firstWord in setOf("INSERT", "UPDATE", "DELETE")
    }

    companion object {
        const val DEFAULT_SPANNER_EMULATOR_PORT = 9010
        private const val SHUTDOWN_TIMEOUT_SECONDS = 2L
    }
}
