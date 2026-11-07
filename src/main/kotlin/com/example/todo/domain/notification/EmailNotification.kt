package com.example.todo.domain.notification

data class EmailNotification(
    val to: String,
    val subject: String,
    val content: String,
)

data class EmailSendResult(
    val messageId: String,
    val status: String,
)

sealed class EmailNotificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class EmailClientException(
    message: String,
    cause: Throwable? = null,
) : EmailNotificationException(message, cause)

class EmailServerException(
    message: String,
    cause: Throwable? = null,
) : EmailNotificationException(message, cause)

