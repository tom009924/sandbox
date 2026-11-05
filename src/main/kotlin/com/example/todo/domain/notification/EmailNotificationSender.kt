package com.example.todo.domain.notification

fun interface EmailNotificationSender {
    fun send(notification: EmailNotification): EmailSendResult
}

