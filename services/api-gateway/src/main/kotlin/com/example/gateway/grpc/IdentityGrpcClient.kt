package com.example.gateway.grpc

import com.example.gateway.config.IdentityGrpcProperties
import com.example.identity.grpc.v1.AuthTokenResponse
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.LoginRequest
import com.example.identity.grpc.v1.RegisterRequest
import com.example.identity.grpc.v1.TodoPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest
import com.example.identity.grpc.v1.UserMessage
import com.example.identity.grpc.v1.UserSettingsMessage
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.util.UUID
import java.util.concurrent.TimeUnit

class IdentityGrpcClient(
    private val channel: ManagedChannel,
    private val internalApiKey: String,
    private val timeoutMillis: Long,
) : AutoCloseable {

    constructor(properties: IdentityGrpcProperties) : this(
        channel = NettyChannelBuilder.forAddress(properties.grpcHost, properties.grpcPort)
            .usePlaintext()
            .build(),
        internalApiKey = properties.internalApiKey,
        timeoutMillis = properties.timeout.toMillis(),
    )

    private fun stub(): IdentityServiceGrpc.IdentityServiceBlockingStub {
        val headers = Metadata().apply {
            put(INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        return IdentityServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun register(username: String, password: String): UserMessage =
        stub().register(
            RegisterRequest.newBuilder()
                .setUsername(username)
                .setPassword(password)
                .build(),
        )

    fun login(username: String, password: String): AuthTokenResponse =
        stub().login(
            LoginRequest.newBuilder()
                .setUsername(username)
                .setPassword(password)
                .build(),
        )

    fun getUserSettings(userId: UUID): UserSettingsMessage =
        stub().getUserSettings(
            GetUserSettingsRequest.newBuilder()
                .setUserId(userId.toString())
                .build(),
        )

    fun updateUserSettings(
        userId: UUID,
        defaultPriority: TodoPriority,
        defaultEstimatedMinutes: Int?,
        maxActiveTodos: Int?,
    ): UserSettingsMessage {
        val builder = UpdateUserSettingsRequest.newBuilder()
            .setUserId(userId.toString())
            .setDefaultPriority(defaultPriority)
        defaultEstimatedMinutes?.let { builder.setDefaultEstimatedMinutes(it) }
        maxActiveTodos?.let { builder.setMaxActiveTodos(it) }
        return stub().updateUserSettings(builder.build())
    }

    fun listUsers(): List<UserMessage> =
        stub().listUsers(ListUsersRequest.getDefaultInstance()).usersList

    override fun close() {
        channel.shutdown()
        if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            channel.shutdownNow()
        }
    }

    companion object {
        const val INTERNAL_API_KEY_HEADER = "x-internal-api-key"
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        val INTERNAL_API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of(INTERNAL_API_KEY_HEADER, Metadata.ASCII_STRING_MARSHALLER)
    }
}
