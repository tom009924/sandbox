package com.example.todo.infrastructure.identity

import com.example.grpc.common.InternalApiKeyGrpcInterceptor
import com.example.identity.grpc.v1.BatchGetUsersRequest
import com.example.identity.grpc.v1.CountUsersRequest
import com.example.identity.grpc.v1.CountUsersResponse
import com.example.identity.grpc.v1.GetUserByUsernameRequest
import com.example.identity.grpc.v1.GetUserRequest
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.ListUsersResponse
import com.example.identity.grpc.v1.UserMessage
import com.example.identity.grpc.v1.UserSettingsMessage
import com.example.todo.domain.model.TodoPriority
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import com.example.identity.grpc.v1.TodoPriority as ProtoTodoPriority

class GrpcIdentityClientTest {
    private val userId = UUID.randomUUID()
    private val internalApiKey = "test-internal-key"
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun setUp() {
        val serverName = InProcessServerBuilder.generateName()
        val authInterceptor = object : ServerInterceptor {
            override fun <ReqT, RespT> interceptCall(
                call: ServerCall<ReqT, RespT>,
                headers: Metadata,
                next: ServerCallHandler<ReqT, RespT>,
            ): ServerCall.Listener<ReqT> {
                val provided = headers.get(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY)
                return if (provided == internalApiKey) {
                    next.startCall(call, headers)
                } else {
                    call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid key"), Metadata())
                    object : ServerCall.Listener<ReqT>() {}
                }
            }
        }
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(FakeIdentityService(userId), authInterceptor))
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `GrpcIdentityUserRepository fetches user, batch, username, count, and maps NOT_FOUND to null`() {
        val repository = GrpcIdentityUserRepository(channel, internalApiKey, 5000L)

        assertEquals("alice", repository.findById(userId)?.username)
        assertNull(repository.findById(UUID.randomUUID()))
        assertEquals(userId, repository.findByUsername("alice")?.id)
        assertNull(repository.findByUsername("unknown"))
        assertEquals(listOf("alice"), repository.findAllByIdIn(listOf(userId, userId)).map { it.username })
        assertEquals(listOf("alice"), repository.findAll().map { it.username })
        assertEquals(42L, repository.count())
    }

    @Test
    fun `GrpcIdentityUserSettingsRepository fetches user settings and maps NOT_FOUND to null`() {
        val repository = GrpcIdentityUserSettingsRepository(channel, internalApiKey, 5000L)

        val settings = repository.findByUserId(userId)

        assertEquals(TodoPriority.HIGH, settings?.defaultPriority)
        assertEquals(30, settings?.defaultEstimatedMinutes)
        assertEquals(10, settings?.maxActiveTodos)
        assertNull(repository.findByUserId(UUID.randomUUID()))
    }

    private class FakeIdentityService(
        private val knownUserId: UUID,
    ) : IdentityServiceGrpc.IdentityServiceImplBase() {
        override fun getUser(request: GetUserRequest, responseObserver: StreamObserver<UserMessage>) {
            if (request.id == knownUserId.toString()) {
                responseObserver.onNext(userMessage())
                responseObserver.onCompleted()
            } else {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Not found").asRuntimeException())
            }
        }

        override fun batchGetUsers(request: BatchGetUsersRequest, responseObserver: StreamObserver<ListUsersResponse>) {
            val users = if (knownUserId.toString() in request.idsList) listOf(userMessage()) else emptyList()
            responseObserver.onNext(ListUsersResponse.newBuilder().addAllUsers(users).build())
            responseObserver.onCompleted()
        }

        override fun listUsers(request: ListUsersRequest, responseObserver: StreamObserver<ListUsersResponse>) {
            responseObserver.onNext(ListUsersResponse.newBuilder().addUsers(userMessage()).build())
            responseObserver.onCompleted()
        }

        override fun getUserByUsername(
            request: GetUserByUsernameRequest,
            responseObserver: StreamObserver<UserMessage>,
        ) {
            if (request.username == "alice") {
                responseObserver.onNext(userMessage())
                responseObserver.onCompleted()
            } else {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Not found").asRuntimeException())
            }
        }

        override fun countUsers(request: CountUsersRequest, responseObserver: StreamObserver<CountUsersResponse>) {
            responseObserver.onNext(CountUsersResponse.newBuilder().setCount(42L).build())
            responseObserver.onCompleted()
        }

        override fun getUserSettings(
            request: GetUserSettingsRequest,
            responseObserver: StreamObserver<UserSettingsMessage>,
        ) {
            if (request.userId == knownUserId.toString()) {
                responseObserver.onNext(
                    UserSettingsMessage.newBuilder()
                        .setUserId(knownUserId.toString())
                        .setDefaultPriority(ProtoTodoPriority.HIGH)
                        .setDefaultEstimatedMinutes(30)
                        .setMaxActiveTodos(10)
                        .build(),
                )
                responseObserver.onCompleted()
            } else {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Not found").asRuntimeException())
            }
        }

        private fun userMessage(): UserMessage = UserMessage.newBuilder()
            .setId(knownUserId.toString())
            .setUsername("alice")
            .build()
    }
}
