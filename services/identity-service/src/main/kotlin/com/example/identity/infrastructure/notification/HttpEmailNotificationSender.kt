package com.example.identity.infrastructure.notification

import com.example.identity.domain.notification.EmailNotification
import com.example.identity.domain.notification.EmailNotificationException
import com.example.identity.domain.notification.EmailNotificationSender
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

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

    override fun send(notification: EmailNotification) {
        val requestBody = EmailSendRequest(
            to = notification.to,
            subject = notification.subject,
            content = notification.content,
        )

        try {
            restClient.post()
                .uri("/api/v1/emails")
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, clientResponse ->
                    val errorBody = clientResponse.body.bufferedReader().use { it.readText() }
                    throw EmailNotificationException("Client error (${clientResponse.statusCode.value()}): $errorBody")
                }
                .onStatus(HttpStatusCode::is5xxServerError) { _, serverResponse ->
                    val errorBody = serverResponse.body.bufferedReader().use { it.readText() }
                    throw EmailNotificationException("Server error (${serverResponse.statusCode.value()}): $errorBody")
                }
                .toBodilessEntity()
        } catch (ex: RestClientException) {
            throw EmailNotificationException("Failed to send email notification", ex)
        }
    }

    private data class EmailSendRequest(
        val to: String,
        val subject: String,
        val content: String,
    )
}
