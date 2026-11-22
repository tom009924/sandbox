package com.example.todo.infrastructure.notification

import com.example.todo.domain.notification.EmailClientException
import com.example.todo.domain.notification.EmailNotification
import com.example.todo.domain.notification.EmailServerException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private const val TIMEOUT_MILLIS = 200L
private const val FIXED_DELAY_MILLIS = 1500
private const val DEFAULT_TEST_TIMEOUT_SECONDS = 3L

@WireMockTest
class HttpEmailNotificationSenderWireMockTest {

    @Test
    fun `send successfully posts email to external service and returns result`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        stubFor(
            post(urlEqualTo("/api/v1/emails"))
                .willReturn(
                    aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                                "id": "msg-test-123",
                                "status": "QUEUED"
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val properties = EmailNotificationProperties(
            baseUrl = wmRuntimeInfo.httpBaseUrl,
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

        verify(
            postRequestedFor(urlEqualTo("/api/v1/emails"))
                .withHeader("Authorization", equalTo("Bearer secret-token-xyz"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(
                    equalToJson(
                        """
                        {
                            "to": "alice@example.com",
                            "subject": "Todo Task Reminder",
                            "content": "You have tasks due today."
                        }
                        """.trimIndent(),
                    ),
                ),
        )
    }

    @Test
    fun `send throws EmailClientException when external service returns 400 Bad Request`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        stubFor(
            post(urlEqualTo("/api/v1/emails"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":"Invalid email address format"}"""),
                ),
        )

        val properties = EmailNotificationProperties(
            baseUrl = wmRuntimeInfo.httpBaseUrl,
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
    fun `send throws EmailClientException when external service returns 401 Unauthorized`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        stubFor(
            post(urlEqualTo("/api/v1/emails"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":"Invalid API key"}"""),
                ),
        )

        val properties = EmailNotificationProperties(
            baseUrl = wmRuntimeInfo.httpBaseUrl,
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
    fun `send throws EmailServerException when external service returns 500 Internal Server Error`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        stubFor(
            post(urlEqualTo("/api/v1/emails"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":"Service temporarily unavailable"}"""),
                ),
        )

        val properties = EmailNotificationProperties(
            baseUrl = wmRuntimeInfo.httpBaseUrl,
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
    fun `send throws EmailServerException when request times out`(
        wmRuntimeInfo: WireMockRuntimeInfo,
    ) {
        stubFor(
            post(urlEqualTo("/api/v1/emails"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFixedDelay(FIXED_DELAY_MILLIS)
                        .withBody("""{"id":"delayed","status":"SENT"}"""),
                ),
        )

        val properties = EmailNotificationProperties(
            baseUrl = wmRuntimeInfo.httpBaseUrl,
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
}
