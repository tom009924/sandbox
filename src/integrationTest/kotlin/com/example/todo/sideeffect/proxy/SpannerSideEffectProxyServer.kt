package com.example.todo.sideeffect.proxy

import com.example.todo.sideeffect.DbOperation
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import tools.jackson.databind.ObjectMapper
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class SpannerProxyConfig(
    val targetHost: String = "localhost",
    val targetPort: Int = 9010,
    val grpcListenPort: Int = 0,
    val adminListenPort: Int = 0,
    val initialAllowedTables: Set<String> = emptySet(),
)

/**
 * Cloud Spanner への通信を透過中継しながら副作用（書き込み/更新）を記録し、
 * テスト向けにアサーション API を提供する Spanner 専用プロキシサーバー。
 */
internal class SpannerSideEffectProxyServer(
    val config: SpannerProxyConfig = SpannerProxyConfig(),
) : Closeable {

    val registry = SpannerSideEffectRegistry(config.initialAllowedTables)
    private val objectMapper = ObjectMapper()

    private val targetChannel: ManagedChannel = ManagedChannelBuilder
        .forAddress(config.targetHost, config.targetPort)
        .usePlaintext()
        .build()

    private val grpcServer: Server = ServerBuilder
        .forPort(config.grpcListenPort)
        .fallbackHandlerRegistry(SpannerGrpcProxy(targetChannel, registry))
        .build()

    private val adminHttpServer: HttpServer = HttpServer.create(InetSocketAddress(config.adminListenPort), 0)
    private val httpExecutor = Executors.newCachedThreadPool()

    val grpcPort: Int
    val adminPort: Int

    init {
        grpcServer.start()
        grpcPort = grpcServer.port

        setupAdminRoutes()
        adminHttpServer.executor = httpExecutor
        adminHttpServer.start()
        adminPort = adminHttpServer.address.port
    }

    private fun setupAdminRoutes() {
        adminHttpServer.createContext("/__spanner_side_effects", AdminHandler())
    }

    override fun close() {
        adminHttpServer.stop(0)
        httpExecutor.shutdownNow()

        grpcServer.shutdownNow()
        targetChannel.shutdownNow()

        grpcServer.awaitTermination(WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        targetChannel.awaitTermination(WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private inner class AdminHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod

            when {
                path == "/__spanner_side_effects/reset" && method == "POST" -> {
                    registry.startTracking()
                    sendJsonResponse(exchange, HTTP_OK, mapOf("status" to "ok"))
                }
                path == "/__spanner_side_effects/unverified" && method == "GET" -> {
                    val summary = UnverifiedSpannerSummary(registry.getUnverifiedMutations())
                    sendJsonResponse(exchange, HTTP_OK, summary)
                }
                path == "/__spanner_side_effects/verify" && method == "POST" -> {
                    val body = readJsonBody<VerifyDbRequest>(exchange)
                    val result = registry.verify(
                        operation = DbOperation.valueOf(body.operation.uppercase()),
                        table = body.table,
                        count = body.count,
                    )
                    sendJsonResponse(exchange, if (result.success) HTTP_OK else HTTP_BAD_REQUEST, result)
                }
                path == "/__spanner_side_effects/allow-table" && method == "POST" -> {
                    val body = readJsonBody<AllowTableRequest>(exchange)
                    registry.allowTable(body.table)
                    sendJsonResponse(exchange, HTTP_OK, mapOf("status" to "ok"))
                }
                else -> sendJsonResponse(exchange, HTTP_NOT_FOUND, mapOf("error" to "Not Found"))
            }
        }
    }

    private inline fun <reified T> readJsonBody(exchange: HttpExchange): T {
        return InputStreamReader(exchange.requestBody, Charsets.UTF_8).use { reader ->
            objectMapper.readValue(reader, T::class.java)
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: Any) {
        val bytes = objectMapper.writeValueAsBytes(body)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        private const val WAIT_TIMEOUT_SEC = 2L

        @JvmStatic
        fun main(args: Array<String>) {
            val spannerTarget = System.getenv("SPANNER_EMULATOR_TARGET") ?: "localhost:9010"
            val (spannerHost, spannerPortStr) = spannerTarget.split(":").let { it[0] to it[1] }
            val grpcPort = (System.getenv("SPANNER_PROXY_PORT") ?: "9015").toInt()
            val adminPort = (System.getenv("SPANNER_PROXY_ADMIN_PORT") ?: "9991").toInt()

            val config = SpannerProxyConfig(
                targetHost = spannerHost,
                targetPort = spannerPortStr.toInt(),
                grpcListenPort = grpcPort,
                adminListenPort = adminPort,
                initialAllowedTables = setOf("idempotency_records"),
            )

            val server = SpannerSideEffectProxyServer(config)
            println("SpannerSideEffectProxyServer started successfully:")
            println(" - gRPC Proxy: localhost:${server.grpcPort} -> $spannerTarget")
            println(" - Admin API:  localhost:${server.adminPort}")

            Runtime.getRuntime().addShutdownHook(Thread {
                server.close()
                println("SpannerSideEffectProxyServer stopped.")
            })
        }
    }
}

internal data class VerifyDbRequest(
    val operation: String = "",
    val table: String = "",
    val count: Int = 1,
)

internal data class AllowTableRequest(
    val table: String = "",
)
