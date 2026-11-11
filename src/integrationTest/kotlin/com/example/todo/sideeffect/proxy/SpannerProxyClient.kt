package com.example.todo.sideeffect.proxy

import com.example.todo.sideeffect.DbOperation
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Spanner 専用プロキシサーバーと通信し、データベース更新副作用を検証するクライアント。
 */
internal class SpannerProxyClient(
    private val adminBaseUrl: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build(),
) {
    private val objectMapper = ObjectMapper()

    fun startTracking() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$adminBaseUrl/__spanner_side_effects/reset"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() == SpannerSideEffectProxyServer.HTTP_OK) {
            "Failed to reset Spanner tracking: status ${response.statusCode()}"
        }
    }

    fun verifyDatabase(operation: DbOperation, table: String, count: Int = 1) {
        val payload = objectMapper.writeValueAsString(
            VerifyDbRequest(operation = operation.name, table = table, count = count),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$adminBaseUrl/__spanner_side_effects/verify"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val result = objectMapper.readValue(response.body(), VerificationResult::class.java)
        assertTrue(result.success, result.message)
    }

    fun allowTable(table: String) {
        val payload = objectMapper.writeValueAsString(AllowTableRequest(table = table))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$adminBaseUrl/__spanner_side_effects/allow-table"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() == SpannerSideEffectProxyServer.HTTP_OK) {
            "Failed to allow table on Spanner proxy: status ${response.statusCode()}"
        }
    }

    fun assertNoUnverifiedMutations() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$adminBaseUrl/__spanner_side_effects/unverified"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val summary = objectMapper.readValue(response.body(), UnverifiedSpannerSummary::class.java)
        assertTrue(summary.isEmpty, summary.toErrorMessage())
    }

    private companion object {
        const val CONNECT_TIMEOUT_SEC = 3L
    }
}
