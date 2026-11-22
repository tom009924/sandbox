package com.example.todo.infrastructure.identity

import com.example.grpc.common.InternalApiKeyGrpcInterceptor
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.UserSettingsMessage
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.UserSettingsRepository
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.MetadataUtils
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.example.identity.grpc.v1.TodoPriority as ProtoTodoPriority

class GrpcIdentityUserSettingsRepository(
    private val channel: Channel,
    private val internalApiKey: String,
    private val timeoutMillis: Long,
) : UserSettingsRepository {

    private fun stub(): IdentityServiceGrpc.IdentityServiceBlockingStub {
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        return IdentityServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun findByUserId(userId: UUID): UserSettings? = try {
        stub().getUserSettings(
            GetUserSettingsRequest.newBuilder().setUserId(userId.toString()).build(),
        ).toDomain()
    } catch (ex: StatusRuntimeException) {
        if (ex.status.code == Status.Code.NOT_FOUND) null else throw ex
    }

    override fun save(userSettings: UserSettings): UserSettings {
        throw UnsupportedOperationException(
            "UserSettings writes are owned by identity-service; " +
                "direct save is disabled when identity.service.enabled=true",
        )
    }

    private fun UserSettingsMessage.toDomain() = UserSettings(
        userId = UUID.fromString(userId),
        defaultPriority = defaultPriority.toDomain(),
        defaultEstimatedMinutes = if (hasDefaultEstimatedMinutes()) defaultEstimatedMinutes else null,
        maxActiveTodos = if (hasMaxActiveTodos()) maxActiveTodos else null,
    )

    private fun ProtoTodoPriority.toDomain(): TodoPriority = when (this) {
        ProtoTodoPriority.LOW -> TodoPriority.LOW
        ProtoTodoPriority.MEDIUM,
        ProtoTodoPriority.TODO_PRIORITY_UNSPECIFIED,
        ProtoTodoPriority.UNRECOGNIZED,
        -> TodoPriority.MEDIUM
        ProtoTodoPriority.HIGH -> TodoPriority.HIGH
    }
}
