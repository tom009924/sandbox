package com.example.todo

import com.example.todo.domain.event.TodoEvent
import tools.jackson.databind.ObjectMapper
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.pubsub.v1.AcknowledgeRequest
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.PullRequest
import com.google.pubsub.v1.PushConfig
import com.google.pubsub.v1.TopicName
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Data class representing an event received from Pub/Sub topic during integration tests.
 */
data class ReceivedTodoEvent(
    val event: TodoEvent,
    val attributes: Map<String, String>,
)

/**
 * Helper utility for integration tests to pull and verify events published to Google Cloud Pub/Sub Emulator.
 */
class PubSubTestReceiver(
    private val projectId: String,
    private val topicId: String,
    subscriptionId: String = "todo-events-integration-test-sub",
    emulatorHost: String?,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : Closeable {

    private val logger = LoggerFactory.getLogger(PubSubTestReceiver::class.java)
    val isAvailable: Boolean
    private val channel: ManagedChannel?
    private val subscriberStub: GrpcSubscriberStub?
    private val subscriptionName: ProjectSubscriptionName = ProjectSubscriptionName.of(projectId, subscriptionId)

    init {
        val host = emulatorHost?.trim().orEmpty()
        if (host.isNotEmpty()) {
            var initializedChannel: ManagedChannel? = null
            var initializedStub: GrpcSubscriberStub? = null
            var available = false
            try {
                val managedChannel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()
                initializedChannel = managedChannel
                val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(managedChannel))
                val credentialsProvider = NoCredentialsProvider.create()

                ensureTopicAndSubscription(channelProvider, credentialsProvider)

                val stubSettings = SubscriberStubSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider)
                    .setCredentialsProvider(credentialsProvider)
                    .build()
                initializedStub = GrpcSubscriberStub.create(stubSettings)
                available = true
            } catch (e: IOException) {
                logger.warn("Pub/Sub emulator is not available at {}: {}", host, e.message)
            }
            channel = initializedChannel
            subscriberStub = initializedStub
            isAvailable = available
        } else {
            channel = null
            subscriberStub = null
            isAvailable = false
        }
    }

    private fun ensureTopicAndSubscription(
        channelProvider: FixedTransportChannelProvider,
        credentialsProvider: NoCredentialsProvider,
    ) {
        val topicName = TopicName.of(projectId, topicId)

        val topicAdminSettings = TopicAdminSettings.newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        TopicAdminClient.create(topicAdminSettings).use { client ->
            try {
                client.getTopic(topicName)
            } catch (e: NotFoundException) {
                logger.info("Topic $topicName not found in emulator ({}), creating it.", e.message)
                client.createTopic(topicName)
            }
        }

        val subscriptionAdminSettings = SubscriptionAdminSettings.newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        SubscriptionAdminClient.create(subscriptionAdminSettings).use { client ->
            try {
                client.getSubscription(subscriptionName.toString())
            } catch (e: NotFoundException) {
                logger.info("Subscription $subscriptionName not found in emulator ({}), creating it.", e.message)
                try {
                    client.createSubscription(
                        subscriptionName.toString(),
                        topicName.toString(),
                        PushConfig.getDefaultInstance(),
                        ACK_DEADLINE_SECONDS,
                    )
                } catch (alreadyExists: AlreadyExistsException) {
                    logger.debug("Subscription already created concurrently: {}", alreadyExists.message)
                }
            }
        }
    }

    /**
     * Pulls up to [maxMessages] events from the subscription, waiting up to [maxWait].
     */
    @Suppress("DEPRECATION")
    fun pullEvents(
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
        maxWait: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    ): List<ReceivedTodoEvent> {
        val stub = subscriberStub ?: return emptyList()
        val deadline = System.currentTimeMillis() + maxWait.toMillis()
        val result = mutableListOf<ReceivedTodoEvent>()

        while (System.currentTimeMillis() < deadline && result.size < maxMessages) {
            val request = PullRequest.newBuilder()
                .setMaxMessages(maxMessages - result.size)
                .setSubscription(subscriptionName.toString())
                .setReturnImmediately(true)
                .build()
            val response = stub.pullCallable().call(request)
            if (response.receivedMessagesList.isNotEmpty()) {
                val ackIds = mutableListOf<String>()
                for (received in response.receivedMessagesList) {
                    ackIds.add(received.ackId)
                    val message = received.message
                    val event = objectMapper.readValue(message.data.toStringUtf8(), TodoEvent::class.java)
                    result.add(ReceivedTodoEvent(event = event, attributes = message.attributesMap))
                }
                stub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                        .setSubscription(subscriptionName.toString())
                        .addAllAckIds(ackIds)
                        .build(),
                )
            } else {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        return result
    }

    /**
     * Drains and acknowledges all pending messages in the subscription.
     */
    @Suppress("DEPRECATION")
    fun drain() {
        val stub = subscriberStub ?: return
        while (true) {
            val request = PullRequest.newBuilder()
                .setMaxMessages(DRAIN_BATCH_SIZE)
                .setSubscription(subscriptionName.toString())
                .setReturnImmediately(true)
                .build()
            val response = stub.pullCallable().call(request)
            if (response.receivedMessagesList.isEmpty()) {
                break
            }
            val ackIds = response.receivedMessagesList.map { it.ackId }
            stub.acknowledgeCallable().call(
                AcknowledgeRequest.newBuilder()
                    .setSubscription(subscriptionName.toString())
                    .addAllAckIds(ackIds)
                    .build(),
            )
        }
    }

    override fun close() {
        subscriberStub?.close()
        channel?.shutdown()
        channel?.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val DEFAULT_MAX_MESSAGES = 10
        const val DEFAULT_TIMEOUT_SECONDS = 3L
        const val POLL_INTERVAL_MS = 100L
        const val DRAIN_BATCH_SIZE = 100
        const val ACK_DEADLINE_SECONDS = 10
        const val TERMINATION_TIMEOUT_SECONDS = 5L
    }
}
