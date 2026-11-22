package com.example.gateway

import com.example.gateway.grpc.IdentityGrpcClient
import com.example.gateway.security.AdminApiKeyFilter
import com.example.identity.grpc.v1.AuthTokenResponse
import com.example.identity.grpc.v1.GetUserSettingsRequest
import com.example.identity.grpc.v1.IdentityServiceGrpc
import com.example.identity.grpc.v1.ListUsersRequest
import com.example.identity.grpc.v1.ListUsersResponse
import com.example.identity.grpc.v1.LoginRequest
import com.example.identity.grpc.v1.RegisterRequest
import com.example.identity.grpc.v1.TodoPriority
import com.example.identity.grpc.v1.UpdateUserSettingsRequest
import com.example.identity.grpc.v1.UserMessage
import com.example.identity.grpc.v1.UserSettingsMessage
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiGatewayGrpcIntegrationTest.InProcessGrpcConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiGatewayGrpcIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val grpcServer: Server,
) {

    @AfterAll
    fun tearDown() {
        grpcServer.shutdownNow()
    }

    @Test
    fun `REST gateway calls identity-service via gRPC and maps statuses`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"Password123!"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.username").value("alice"))

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"duplicate","password":"Password123!"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("User already exists"))

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"Password123!"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("fake-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))

        val jwt = createJwt(FAKE_USER_ID, "alice")
        mockMvc.perform(
            get("/api/user/settings")
                .header("Authorization", "Bearer $jwt"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultPriority").value("MEDIUM"))

        mockMvc.perform(
            put("/api/user/settings")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":0}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
            .andExpect(jsonPath("$.defaultEstimatedMinutes").value(0))

        mockMvc.perform(
            get("/api/admin/users")
                .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "admin-secret-key"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].username").value("alice"))
    }

    @Test
    fun `validates identity request constraints against OpenAPI contract`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"short6"}"""),
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bob","password":"12345"}"""),
        )
            .andExpect(status().isBadRequest)

        val jwt = createJwt(FAKE_USER_ID, "alice")
        mockMvc.perform(
            put("/api/user/settings")
                .header("Authorization", "Bearer $jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":-1}"""),
        )
            .andExpect(status().isBadRequest)
    }

    private fun createJwt(userId: UUID, username: String): String {
        val secret = "default-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256"
        val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(key)
            .compact()
    }

    @TestConfiguration
    class InProcessGrpcConfig {
        private val serverName = InProcessServerBuilder.generateName()

        @Bean
        fun inProcessGrpcServer(): Server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(FakeIdentityGrpcService())
            .build()
            .start()

        @Bean(destroyMethod = "close")
        @Primary
        fun testIdentityGrpcClient(inProcessGrpcServer: Server): IdentityGrpcClient {
            check(!inProcessGrpcServer.isShutdown)
            val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
            return IdentityGrpcClient(channel, "internal-secret-key", 5000L)
        }
    }

    private class FakeIdentityGrpcService : IdentityServiceGrpc.IdentityServiceImplBase() {
        override fun register(
            request: RegisterRequest,
            responseObserver: StreamObserver<UserMessage>,
        ) {
            if (request.username == "duplicate") {
                responseObserver.onError(
                    Status.ALREADY_EXISTS.withDescription("User already exists").asRuntimeException(),
                )
                return
            }
            responseObserver.onNext(
                UserMessage.newBuilder()
                    .setId(FAKE_USER_ID.toString())
                    .setUsername(request.username)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun login(
            request: LoginRequest,
            responseObserver: StreamObserver<AuthTokenResponse>,
        ) {
            responseObserver.onNext(
                AuthTokenResponse.newBuilder()
                    .setToken("fake-token")
                    .setTokenType("Bearer")
                    .setExpiresIn(3600L)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun getUserSettings(
            request: GetUserSettingsRequest,
            responseObserver: StreamObserver<UserSettingsMessage>,
        ) {
            responseObserver.onNext(
                UserSettingsMessage.newBuilder()
                    .setUserId(request.userId)
                    .setDefaultPriority(TodoPriority.MEDIUM)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun updateUserSettings(
            request: UpdateUserSettingsRequest,
            responseObserver: StreamObserver<UserSettingsMessage>,
        ) {
            val builder = UserSettingsMessage.newBuilder()
                .setUserId(request.userId)
                .setDefaultPriority(request.defaultPriority)
            if (request.hasDefaultEstimatedMinutes()) {
                builder.setDefaultEstimatedMinutes(request.defaultEstimatedMinutes)
            }
            responseObserver.onNext(builder.build())
            responseObserver.onCompleted()
        }

        override fun listUsers(
            request: ListUsersRequest,
            responseObserver: StreamObserver<ListUsersResponse>,
        ) {
            responseObserver.onNext(
                ListUsersResponse.newBuilder()
                    .addUsers(
                        UserMessage.newBuilder()
                            .setId(FAKE_USER_ID.toString())
                            .setUsername("alice")
                            .build(),
                    )
                    .build(),
            )
            responseObserver.onCompleted()
        }
    }

    private companion object {
        val FAKE_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
