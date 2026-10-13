package com.example.todo

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class SpannerProxyFaultInjector(
    private val controlUrl: URI = URI.create(
        System.getenv("SPANNER_PROXY_CONTROL_URL") ?: DEFAULT_CONTROL_URL,
    ),
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun failTodoInsert(): AutoCloseable {
        val request = HttpRequest.newBuilder(controlUrl.resolve(INSERT_FAILURE_PATH))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        requireSuccessful(client.send(request, HttpResponse.BodyHandlers.ofString()), "enable INSERT failure")

        return AutoCloseable {
            val removeRequest = HttpRequest.newBuilder(controlUrl.resolve(INSERT_FAILURE_PATH))
                .DELETE()
                .build()
            requireSuccessful(
                client.send(removeRequest, HttpResponse.BodyHandlers.ofString()),
                "disable INSERT failure",
            )
        }
    }

    private fun requireSuccessful(response: HttpResponse<String>, action: String) {
        check(response.statusCode() in SUCCESS_STATUS_RANGE) {
            "Failed to $action in Spanner proxy: HTTP ${response.statusCode()} ${response.body()}"
        }
    }

    private companion object {
        const val DEFAULT_CONTROL_URL = "http://localhost:8474"
        const val INSERT_FAILURE_PATH = "/faults/insert-into-todos"
        val SUCCESS_STATUS_RANGE = 200..299
    }
}
