package com.example.todo.infrastructure.identity

import com.example.identity.grpc.v1.BatchGetUsersRequest
import com.example.identity.grpc.v1.CountUsersRequest
import com.example.identity.grpc.v1.GetUserByUsernameRequest
import com.example.identity.grpc.v1.GetUserRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.UserMessage
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.MetadataUtils
import java.util.UUID
import java.util.concurrent.TimeUnit

class GrpcIdentityUserRepository(
    private val channel: Channel,
    private val internalApiKey: String,
    private val timeoutMillis: Long,
) : UserRepository {

    private fun stub(): IdentityServiceGrpc.IdentityServiceBlockingStub {
        val headers = Metadata().apply {
            put(INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        return IdentityServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun findById(id: UUID): User? = try {
        stub().getUser(
            GetUserRequest.newBuilder().setId(id.toString()).build(),
        ).toDomain()
    } catch (ex: StatusRuntimeException) {
        if (ex.status.code == Status.Code.NOT_FOUND) null else throw ex
    }

    override fun findAllByIdIn(ids: Collection<UUID>): List<User> {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return emptyList()
        return stub().batchGetUsers(
            BatchGetUsersRequest.newBuilder()
                .addAllIds(uniqueIds.map { it.toString() })
                .build(),
        ).usersList.map { it.toDomain() }
    }

    override fun findAll(): List<User> =
        stub().listUsers(ListUsersRequest.getDefaultInstance()).usersList.map { it.toDomain() }

    override fun findByUsername(username: String): User? = try {
        stub().getUserByUsername(
            GetUserByUsernameRequest.newBuilder().setUsername(username).build(),
        ).toDomain()
    } catch (ex: StatusRuntimeException) {
        if (ex.status.code == Status.Code.NOT_FOUND) null else throw ex
    }

    override fun existsByUsername(username: String): Boolean = findByUsername(username) != null

    override fun save(user: User): User {
        throw UnsupportedOperationException(
            "User writes are owned by identity-service; direct save is disabled when identity.service.enabled=true",
        )
    }

    override fun count(): Long =
        stub().countUsers(CountUsersRequest.getDefaultInstance()).count

    override fun deleteAll() {
        throw UnsupportedOperationException(
            "User writes are owned by identity-service; deleteAll is disabled when identity.service.enabled=true",
        )
    }

    private fun UserMessage.toDomain() = User(
        id = UUID.fromString(id),
        username = username,
        passwordHash = "",
    )

    companion object {
        const val INTERNAL_API_KEY_HEADER = "x-internal-api-key"
        val INTERNAL_API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of(INTERNAL_API_KEY_HEADER, Metadata.ASCII_STRING_MARSHALLER)
    }
}
