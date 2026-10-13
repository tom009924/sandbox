package com.example.spannerproxy

import com.google.protobuf.InvalidProtocolBufferException
import com.google.spanner.v1.ExecuteSqlRequest
import com.google.spanner.v1.SpannerGrpc
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerMethodDefinition
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

private const val EXECUTE_SQL_METHOD = "google.spanner.v1.Spanner/ExecuteSql"

internal class TodoInsertFailure {
    private val enabled = AtomicBoolean()

    fun isEnabled(): Boolean = enabled.get()

    fun enable() {
        enabled.set(true)
    }

    fun disable() {
        enabled.set(false)
    }
}

internal fun shouldFailTodoInsert(
    fullMethodName: String,
    requestMessage: ByteArray,
    todoInsertFailure: TodoInsertFailure,
): Boolean {
    if (fullMethodName != EXECUTE_SQL_METHOD || !todoInsertFailure.isEnabled()) return false
    return try {
        TODO_INSERT_PATTERN.matcher(ExecuteSqlRequest.parseFrom(requestMessage).sql.trimStart()).find()
    } catch (_: InvalidProtocolBufferException) {
        false
    }
}

internal class SpannerGrpcProxy(
    private val upstream: Channel,
    private val todoInsertFailure: TodoInsertFailure,
) {
    private val methods = SpannerGrpc.getServiceDescriptor().methods.associate { method ->
        val descriptor = byteArrayDescriptor(method)
        descriptor.fullMethodName to ServerMethodDefinition.create(
            descriptor,
            forwardingHandler(descriptor),
        )
    }

    fun lookupMethod(fullMethodName: String): ServerMethodDefinition<ByteArray, ByteArray>? =
        methods[fullMethodName]

    private fun forwardingHandler(
        descriptor: MethodDescriptor<ByteArray, ByteArray>,
    ) = ServerCallHandler<ByteArray, ByteArray> { serverCall, headers ->
        val clientCall = upstream.newCall(descriptor, CallOptions.DEFAULT)
        val terminated = AtomicBoolean()
        clientCall.start(
            object : ClientCall.Listener<ByteArray>() {
                override fun onHeaders(headers: Metadata) {
                    if (!terminated.get()) serverCall.sendHeaders(headers)
                }

                override fun onMessage(message: ByteArray) {
                    if (!terminated.get()) {
                        serverCall.sendMessage(message)
                        clientCall.request(1)
                    }
                }

                override fun onClose(status: Status, trailers: Metadata) {
                    if (terminated.compareAndSet(false, true)) serverCall.close(status, trailers)
                }
            },
            headers,
        )
        clientCall.request(1)
        serverCall.request(1)

        object : ServerCall.Listener<ByteArray>() {
            override fun onMessage(message: ByteArray) {
                if (terminated.get()) return
                if (shouldFailTodoInsert(descriptor.fullMethodName, message, todoInsertFailure)) {
                    if (terminated.compareAndSet(false, true)) {
                        serverCall.close(
                            Status.UNAVAILABLE.withDescription("Injected Todo INSERT failure"),
                            Metadata(),
                        )
                        clientCall.cancel("Injected Todo INSERT failure", null)
                    }
                    return
                }
                clientCall.sendMessage(message)
                serverCall.request(1)
            }

            override fun onHalfClose() {
                if (!terminated.get()) clientCall.halfClose()
            }

            override fun onCancel() {
                if (terminated.compareAndSet(false, true)) {
                    clientCall.cancel("Downstream client cancelled", null)
                }
            }
        }
    }

    private fun byteArrayDescriptor(
        original: MethodDescriptor<*, *>,
    ): MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(original.type)
            .setFullMethodName(original.fullMethodName)
            .setRequestMarshaller(BYTE_ARRAY_MARSHALLER)
            .setResponseMarshaller(BYTE_ARRAY_MARSHALLER)
            .build()

    private companion object {
        val BYTE_ARRAY_MARSHALLER = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }
    }
}

internal class SpannerProxyHandlerRegistry(
    private val proxy: SpannerGrpcProxy,
) : io.grpc.HandlerRegistry() {
    override fun lookupMethod(
        methodName: String,
        authority: String?,
    ): ServerMethodDefinition<*, *>? = proxy.lookupMethod(methodName)
}

internal fun newControlServer(
    address: String,
    port: Int,
    todoInsertFailure: TodoInsertFailure,
): HttpServer {
    val server = HttpServer.create(InetSocketAddress(address, port), 0)
    server.createContext("/healthz") { exchange ->
        if (exchange.requestMethod != "GET") {
            exchange.sendResponse(HttpURLConnection.HTTP_BAD_METHOD)
        } else {
            exchange.sendResponse(HttpURLConnection.HTTP_NO_CONTENT)
        }
    }
    server.createContext("/faults/insert-into-todos") { exchange ->
        when (exchange.requestMethod) {
            "POST" -> {
                todoInsertFailure.enable()
                exchange.sendResponse(HttpURLConnection.HTTP_NO_CONTENT)
            }
            "DELETE" -> {
                todoInsertFailure.disable()
                exchange.sendResponse(HttpURLConnection.HTTP_NO_CONTENT)
            }
            else -> {
                exchange.responseHeaders.add("Allow", "POST, DELETE")
                exchange.sendResponse(HttpURLConnection.HTTP_BAD_METHOD)
            }
        }
    }
    return server
}

private fun HttpExchange.sendResponse(status: Int) {
    sendResponseHeaders(status, -1)
    close()
}

fun main() {
    val proxyAddress = InetSocketAddress(
        System.getenv("SPANNER_PROXY_HOST") ?: "0.0.0.0",
        (System.getenv("SPANNER_PROXY_PORT") ?: "8666").toInt(),
    )
    val controlAddress = System.getenv("SPANNER_PROXY_CONTROL_HOST") ?: "0.0.0.0"
    val controlPort = (System.getenv("SPANNER_PROXY_CONTROL_PORT") ?: "8474").toInt()
    val emulatorHost = System.getenv("SPANNER_EMULATOR_UPSTREAM_HOST") ?: "spanner-emulator"
    val emulatorPort = (System.getenv("SPANNER_EMULATOR_UPSTREAM_PORT") ?: "9010").toInt()
    val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(emulatorHost, emulatorPort)
        .usePlaintext()
        .build()
    val todoInsertFailure = TodoInsertFailure()
    val controlServer = newControlServer(controlAddress, controlPort, todoInsertFailure)
    val grpcServer = NettyServerBuilder
        .forAddress(proxyAddress)
        .fallbackHandlerRegistry(SpannerProxyHandlerRegistry(SpannerGrpcProxy(channel, todoInsertFailure)))
        .build()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            grpcServer.shutdownNow()
            channel.shutdownNow()
            controlServer.stop(0)
        },
    )
    grpcServer.start()
    controlServer.start()
    grpcServer.awaitTermination()
}

private val TODO_INSERT_PATTERN = Pattern.compile(
    """(?is)^INSERT\s+INTO\s+[`"]?todos[`"]?(?:\s|\()""",
)
