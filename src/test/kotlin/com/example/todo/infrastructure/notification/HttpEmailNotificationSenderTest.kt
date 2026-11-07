package com.example.todo.infrastructure.notification

import com.example.todo.domain.notification.EmailClientException
import com.example.todo.domain.notification.EmailNotification
import com.example.todo.domain.notification.EmailServerException
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

private const val TIMEOUT_MILLIS = 200L
private const val FIXED_DELAY_MILLIS = 1000L
private const val DEFAULT_TEST_TIMEOUT_SECONDS = 3L

class HttpEmailNotificationSenderTest {

    private lateinit var mockServer: MockHttpServer

    @BeforeEach
    fun setUp() {
        mockServer = MockHttpServer()
    }

    @AfterEach
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `send successfully posts email to external service and returns result`() {
        mockServer.responseStatusCode = 202
        mockServer.responseBody = """
            {
                "id": "msg-test-123",
                "status": "QUEUED"
            }
        """.trimIndent()

        val properties = EmailNotificationProperties(
            baseUrl = mockServer.baseUrl,
            apiKey = "secret-token-xyz",
            timeout = Duration.ofSeconds(DEFAULT_TEST_TIMEOUT_SECONDS),
        )
        val sender = HttpEmailNotificationSender(properties)

        val notification = EmailNotification(
            to = "alice@example.com",
            subject = "Todo Task Reminder",
            content = "You have tasks due today.",
        )

        val result = sender.send(notification)

        assertEquals("msg-test-123", result.messageId)
        assertEquals("QUEUED", result.status)

        assertEquals(1, mockServer.recordedRequests.size)
        val request = mockServer.recordedRequests.first()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/emails", request.path)
        assertEquals("Bearer secret-token-xyz", request.headers.getFirst("Authorization"))
        assertTrue(request.headers.getFirst("Content-Type")?.contains("application/json") == true)
        assertTrue(request.body.contains("alice@example.com"))
        assertTrue(request.body.contains("Todo Task Reminder"))
        assertTrue(request.body.contains("You have tasks due today."))
    }

    @Test
    fun `send throws EmailClientException when external service returns 400 Bad Request`() {
        mockServer.responseStatusCode = 400
        mockServer.responseBody = """{"error":"Invalid email address format"}"""

        val properties = EmailNotificationProperties(
            baseUrl = mockServer.baseUrl,
            apiKey = "secret-token-xyz",
        )
        val sender = HttpEmailNotificationSender(properties)

        val exception = assertThrows(EmailClientException::class.java) {
            sender.send(
                EmailNotification(
                    to = "invalid-email",
                    subject = "Test",
                    content = "Test content",
                ),
            )
        }

        assertTrue(exception.message!!.contains("400"))
        assertTrue(exception.message!!.contains("Invalid email address format"))
    }

    @Test
    fun `send throws EmailClientException when external service returns 401 Unauthorized`() {
        mockServer.responseStatusCode = 401
        mockServer.responseBody = """{"error":"Invalid API key"}"""

        val properties = EmailNotificationProperties(
            baseUrl = mockServer.baseUrl,
            apiKey = "wrong-key",
        )
        val sender = HttpEmailNotificationSender(properties)

        val exception = assertThrows(EmailClientException::class.java) {
            sender.send(
                EmailNotification(
                    to = "bob@example.com",
                    subject = "Auth Test",
                    content = "Test content",
                ),
            )
        }

        assertTrue(exception.message!!.contains("401"))
    }

    @Test
    fun `send throws EmailServerException when external service returns 500 Internal Server Error`() {
        mockServer.responseStatusCode = 500
        mockServer.responseBody = """{"error":"Service temporarily unavailable"}"""

        val properties = EmailNotificationProperties(
            baseUrl = mockServer.baseUrl,
            apiKey = "secret-token-xyz",
        )
        val sender = HttpEmailNotificationSender(properties)

        val exception = assertThrows(EmailServerException::class.java) {
            sender.send(
                EmailNotification(
                    to = "charlie@example.com",
                    subject = "Server Error Test",
                    content = "Test content",
                ),
            )
        }

        assertTrue(exception.message!!.contains("500"))
    }

    @Test
    fun `send throws EmailServerException when request times out`() {
        mockServer.responseStatusCode = 200
        mockServer.delayMillis = FIXED_DELAY_MILLIS
        mockServer.responseBody = """{"id":"delayed","status":"SENT"}"""

        val properties = EmailNotificationProperties(
            baseUrl = mockServer.baseUrl,
            apiKey = "secret-token-xyz",
            timeout = Duration.ofMillis(TIMEOUT_MILLIS),
        )
        val sender = HttpEmailNotificationSender(properties)

        assertThrows(EmailServerException::class.java) {
            sender.send(
                EmailNotification(
                    to = "dave@example.com",
                    subject = "Timeout Test",
                    content = "Test content",
                ),
            )
        }
    }

    private class MockHttpServer : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://localhost:$port"

        data class RecordedRequest(
            val method: String,
            val path: String,
            val headers: Headers,
            val body: String,
        )

        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var responseStatusCode: Int = 200

        @Volatile
        var responseHeaders: MutableMap<String, String> = mutableMapOf("Content-Type" to "application/json")

        @Volatile
        var responseBody: String = ""

        @Volatile
        var delayMillis: Long = 0L

        init {
            server.createContext("/") { exchange: HttpExchange ->
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                recordedRequests.add(
                    RecordedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        headers = exchange.requestHeaders,
                        body = body,
                    ),
                )
                responseHeaders.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(responseStatusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }
}
