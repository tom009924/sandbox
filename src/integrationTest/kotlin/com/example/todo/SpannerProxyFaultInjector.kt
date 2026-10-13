package com.example.todo

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class SpannerProxyFaultInjector(
    private val controlUrl: URI = URI.create(
        System.getenv("TOXIPROXY_CONTROL_URL") ?: DEFAULT_CONTROL_URL,
    ),
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun resetSpannerConnections(): AutoCloseable {
        val request = HttpRequest.newBuilder(controlUrl.resolve(RESET_TOXIC_PATH))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(RESET_TOXIC))
            .build()
        requireSuccessful(client.send(request, HttpResponse.BodyHandlers.ofString()), "install reset toxic")

        return AutoCloseable {
            val removeRequest = HttpRequest.newBuilder(controlUrl.resolve(RESET_TOXIC_PATH))
                .DELETE()
                .build()
            requireSuccessful(
                client.send(removeRequest, HttpResponse.BodyHandlers.ofString()),
                "remove reset toxic",
            )
        }
    }

    private fun requireSuccessful(response: HttpResponse<String>, action: String) {
        check(response.statusCode() in SUCCESS_STATUS_RANGE) {
            "Failed to $action in Toxiproxy: HTTP ${response.statusCode()} ${response.body()}"
        }
    }

    private companion object {
        const val DEFAULT_CONTROL_URL = "http://localhost:8474"
        const val RESET_TOXIC_PATH = "/proxies/spanner/toxics/spanner-reset"
        val RESET_TOXIC =
            """
            {"name":"spanner-reset","type":"reset_peer","stream":"upstream","toxicity":1.0,"attributes":{"timeout":0}}
            """.trimIndent()
        val SUCCESS_STATUS_RANGE = 200..299
    }
}
