package com.example.todo.sideeffect.proxy

import com.example.todo.sideeffect.DatabaseMutation
import com.example.todo.sideeffect.DbOperation
import com.google.spanner.v1.CommitRequest
import com.google.spanner.v1.ExecuteSqlRequest
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.HandlerRegistry
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerMethodDefinition
import io.grpc.Status
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Spanner の gRPC 通信をエミュレータへ透過中継しつつ、
 * 更新系 RPC（Commit / DML）を傍受してレジストリに記録するプロキシ。
 */
internal class SpannerGrpcProxy(
    private val targetChannel: ManagedChannel,
    private val registry: SpannerSideEffectRegistry,
) : HandlerRegistry() {

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
                override fun onHeaders(responseHeaders: Metadata) {
                    serverCall.sendHeaders(responseHeaders)
                }

                override fun onMessage(message: ByteArray) {
                    serverCall.sendMessage(message)
                }

                override fun onClose(status: Status, trailers: Metadata) {
                    serverCall.close(status, trailers)
                }

                override fun onReady() {
                    serverCall.request(1)
                }
            }

            clientCall.start(clientListener, headers)
            serverCall.request(1)

            object : ServerCall.Listener<ByteArray>() {
                override fun onMessage(message: ByteArray) {
                    try {
                        inspectSideEffect(serverCall.methodDescriptor.fullMethodName, message)
                    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Throwable) {
                        // 検査エラー時も透過中継は継続する
                    }
                    clientCall.sendMessage(message)
                }

                override fun onHalfClose() {
                    clientCall.halfClose()
                }

                override fun onCancel() {
                    clientCall.cancel("Server call cancelled", null)
                }

                override fun onReady() {
                    clientCall.request(1)
                }
            }
        }

        return ServerMethodDefinition.create(methodDescriptor, handler)
    }

    private fun inspectSideEffect(fullMethodName: String, messageBytes: ByteArray) {
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

            registry.recordMutation(
                DatabaseMutation(
                    operation = op,
                    table = table.lowercase(),
                    sql = "Spanner Mutation: $op on $table",
                ),
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
        registry.recordMutation(
            DatabaseMutation(
                operation = operation,
                table = table.lowercase(),
                sql = cleanSql,
            ),
        )
    }

    private fun stripComments(sql: String): String {
        var result = sql
        while (result.startsWith("/*")) {
            val endIndex = result.indexOf("*/")
            result = if (endIndex != -1) {
                result.substring(endIndex + 2).trimStart()
            } else {
                break
            }
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
}
