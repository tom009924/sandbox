package com.example.todo.infrastructure.notification

import com.example.todo.domain.notification.EmailClientException
import com.example.todo.domain.notification.EmailNotification
import com.example.todo.domain.notification.EmailNotificationSender
import com.example.todo.domain.notification.EmailSendResult
import com.example.todo.domain.notification.EmailServerException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

class HttpEmailNotificationSender(
    private val restClient: RestClient,
) : EmailNotificationSender {

    constructor(
        properties: EmailNotificationProperties,
        restClientBuilder: RestClient.Builder = RestClient.builder(),
    ) : this(
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(properties.timeout)
                    setReadTimeout(properties.timeout)
                },
            )
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build(),
    )

    override fun send(notification: EmailNotification): EmailSendResult {
        val requestBody = EmailSendRequest(
            to = notification.to,
            subject = notification.subject,
            content = notification.content,
        )

        try {
            val response = restClient.post()
                .uri("/api/v1/emails")
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, clientResponse ->
                    val errorBody = clientResponse.body.bufferedReader().use { it.readText() }
                    throw EmailClientException("Client error (${clientResponse.statusCode.value()}): $errorBody")
                }
                .onStatus(HttpStatusCode::is5xxServerError) { _, serverResponse ->
                    val errorBody = serverResponse.body.bufferedReader().use { it.readText() }
                    throw EmailServerException("Server error (${serverResponse.statusCode.value()}): $errorBody")
                }
                .body(EmailSendResponse::class.java)
                ?: throw EmailServerException("Empty response received from email notification service")

            return EmailSendResult(
                messageId = response.id,
                status = response.status,
            )
        } catch (e: EmailClientException) {
            throw e
        } catch (e: EmailServerException) {
            throw e
        } catch (e: ResourceAccessException) {
            throw EmailServerException("Failed to communicate with email notification service: ${e.message}", e)
        } catch (e: RestClientResponseException) {
            if (e.statusCode.is4xxClientError) {
                throw EmailClientException("Client error (${e.statusCode.value()}): ${e.responseBodyAsString}", e)
            } else {
                throw EmailServerException("Server error (${e.statusCode.value()}): ${e.responseBodyAsString}", e)
            }
        } catch (e: RestClientException) {
            throw EmailServerException("Unexpected error during email notification: ${e.message}", e)
        }
    }

    private data class EmailSendRequest(
        val to: String,
        val subject: String,
        val content: String,
    )

    private data class EmailSendResponse(
        val id: String = "",
        val status: String = "",
    )
}
