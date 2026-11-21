package com.example.identity.infrastructure.grpc

import com.example.identity.application.AdminUserService
import com.example.identity.application.AuthService
import com.example.identity.application.TokenIssuer
import com.example.identity.application.TokenResult
import com.example.identity.application.UserSettingsService
import com.example.identity.domain.model.AuthenticatedUser
import com.example.identity.domain.model.User
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserRepository
import com.example.identity.domain.repository.UserSettingsRepository
import com.example.identity.domain.security.PasswordEncoder
import com.example.identity.grpc.v1.CountUsersRequest
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.LoginRequest
import com.example.identity.grpc.v1.RegisterRequest
import com.example.identity.grpc.v1.TodoPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class IdentityGrpcServiceTest {
    private val internalApiKey = "test-internal-key"
    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel
    private lateinit var authenticatedStub: IdentityServiceGrpc.IdentityServiceBlockingStub
    private lateinit var rawStub: IdentityServiceGrpc.IdentityServiceBlockingStub

    @BeforeEach
    fun setUp() {
        val userRepo = InMemoryUserRepository()
        val settingsRepo = InMemoryUserSettingsRepository()
        val passwordEncoder = object : PasswordEncoder {
            override fun encode(rawPassword: String): String = "enc:$rawPassword"
            override fun matches(rawPassword: String, encodedPassword: String): Boolean =
                encodedPassword == "enc:$rawPassword"
        }
        val tokenIssuer = object : TokenIssuer {
            override fun issueToken(user: AuthenticatedUser): TokenResult =
                TokenResult("jwt-${user.username}", "Bearer", 3600L)
        }
        val grpcService = IdentityGrpcService(
            authService = AuthService(userRepo, passwordEncoder, tokenIssuer),
            userSettingsService = UserSettingsService(settingsRepo),
            adminUserService = AdminUserService(userRepo),
            userRepository = userRepo,
        )
        val serverName = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(grpcService, InternalApiKeyGrpcInterceptor(internalApiKey)),
            )
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        rawStub = IdentityServiceGrpc.newBlockingStub(channel)
        val headers = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, internalApiKey)
        }
        authenticatedStub = rawStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `rejects unauthenticated gRPC call without internal API key`() {
        val ex = assertThrows(StatusRuntimeException::class.java) {
            rawStub.countUsers(CountUsersRequest.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    @Test
    fun `register, login, settings, and listUsers work over gRPC`() {
        val registered = authenticatedStub.register(
            RegisterRequest.newBuilder().setUsername("alice").setPassword("Password123!").build(),
        )
        assertEquals("alice", registered.username)

        val duplicate = assertThrows(StatusRuntimeException::class.java) {
            authenticatedStub.register(
                RegisterRequest.newBuilder().setUsername("alice").setPassword("Password123!").build(),
            )
        }
        assertEquals(Status.Code.ALREADY_EXISTS, duplicate.status.code)

        val token = authenticatedStub.login(
            LoginRequest.newBuilder().setUsername("alice").setPassword("Password123!").build(),
        )
        assertEquals("jwt-alice", token.token)

        val updated = authenticatedStub.updateUserSettings(
            UpdateUserSettingsRequest.newBuilder()
                .setUserId(registered.id)
                .setDefaultPriority(TodoPriority.HIGH)
                .setDefaultEstimatedMinutes(45)
                .build(),
        )
        assertEquals(TodoPriority.HIGH, updated.defaultPriority)
        assertEquals(45, updated.defaultEstimatedMinutes)

        val fetched = authenticatedStub.getUserSettings(
            GetUserSettingsRequest.newBuilder().setUserId(registered.id).build(),
        )
        assertEquals(TodoPriority.HIGH, fetched.defaultPriority)

        val list = authenticatedStub.listUsers(ListUsersRequest.getDefaultInstance())
        assertEquals(1, list.usersCount)
        assertEquals("alice", list.getUsers(0).username)
    }

    private class InMemoryUserRepository : UserRepository {
        private val store = LinkedHashMap<UUID, User>()
        override fun findById(id: UUID): User? = store[id]
        override fun findAllByIdIn(ids: Collection<UUID>): List<User> = ids.mapNotNull { store[it] }
        override fun findAll(): List<User> = store.values.toList()
        override fun findByUsername(username: String): User? = store.values.firstOrNull { it.username == username }
        override fun existsByUsername(username: String): Boolean = store.values.any { it.username == username }
        override fun save(user: User): User {
            val id = user.id ?: UUID.randomUUID()
            val saved = user.copy(id = id)
            store[id] = saved
            return saved
        }
        override fun count(): Long = store.size.toLong()
        override fun deleteAll() = store.clear()
    }

    private class InMemoryUserSettingsRepository : UserSettingsRepository {
        private val store = LinkedHashMap<UUID, UserSettings>()
        override fun findByUserId(userId: UUID): UserSettings? = store[userId]
        override fun save(userSettings: UserSettings): UserSettings {
            store[userSettings.userId] = userSettings
            return userSettings
        }
    }
}
