package com.example.identity.domain.notification

data class EmailNotification(
    val to: String,
    val subject: String,
    val content: String,
)
