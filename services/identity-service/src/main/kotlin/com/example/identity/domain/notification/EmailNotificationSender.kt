package com.example.identity.domain.notification

interface EmailNotificationSender {
    fun send(notification: EmailNotification)
}

class EmailNotificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
