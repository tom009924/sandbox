package com.example.todo.infrastructure.pubsub

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pubsub")
data class PubSubProperties(
    val projectId: String = "todo-project",
    val topicId: String = "todo-events",
    val emulatorHost: String? = null,
)

