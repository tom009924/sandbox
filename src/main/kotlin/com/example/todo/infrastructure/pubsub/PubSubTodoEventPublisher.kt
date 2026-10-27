package com.example.todo.infrastructure.pubsub

import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventPublisher
import tools.jackson.databind.ObjectMapper
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.TopicName
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

class PubSubTodoEventPublisher(
    private val properties: PubSubProperties,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : TodoEventPublisher, Closeable {

    private val logger = LoggerFactory.getLogger(PubSubTodoEventPublisher::class.java)
    private val topicName: TopicName = TopicName.of(properties.projectId, properties.topicId)
    private val channel: ManagedChannel?
    private val publisher: Publisher?

    init {
        val host = properties.emulatorHost?.trim().orEmpty()
        if (host.isNotEmpty()) {
            val managedChannel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()
            channel = managedChannel
            ensureTopicExists(managedChannel)
            publisher = createEmulatorPublisher(managedChannel)
            logger.info("PubSubTodoEventPublisher initialized using emulator at $host for topic ${topicName.topic}")
        } else {
            channel = null
            publisher = createStandardPublisher()
        }
    }

    override fun publish(event: TodoEvent) {
        val activePublisher = publisher ?: run {
            logger.debug("Publisher not configured; skipping event: $event")
            return
        }

        val jsonBytes = objectMapper.writeValueAsBytes(event)
        val message = PubsubMessage.newBuilder()
            .setData(ByteString.copyFrom(jsonBytes))
            .putAttributes("eventType", event.eventType.name)
            .putAttributes("todoId", event.todoId.toString())
            .build()

        val future = activePublisher.publish(message)
        future.addListener(
            {
                try {
                    val messageId = future.get()
                    logger.debug("Published event ${event.eventType} with messageId: $messageId")
                } catch (e: java.util.concurrent.ExecutionException) {
                    logger.error("Failed to publish event $event", e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.error("Publishing event $event was interrupted", e)
                }
            },
            Runnable::run,
        )
    }

    override fun close() {
        publisher?.shutdown()
        publisher?.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        channel?.shutdown()
        channel?.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun ensureTopicExists(channel: ManagedChannel) {
        val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider = NoCredentialsProvider.create()
        val settings = TopicAdminSettings.newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()

        TopicAdminClient.create(settings).use { client ->
            try {
                client.getTopic(topicName)
            } catch (e: NotFoundException) {
                logger.info("Topic $topicName not found in emulator ({}), creating it.", e.message)
                client.createTopic(topicName)
            }
        }
    }

    private fun createEmulatorPublisher(channel: ManagedChannel): Publisher {
        val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider = NoCredentialsProvider.create()
        return Publisher.newBuilder(topicName)
            .setChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
    }

    private fun createStandardPublisher(): Publisher? =
        try {
            Publisher.newBuilder(topicName).build()
        } catch (e: IOException) {
            logger.warn("Could not create standard PubSub Publisher (no credentials?); events will be skipped", e)
            null
        }

    private companion object {
        const val TERMINATION_TIMEOUT_SECONDS = 5L
    }
}

