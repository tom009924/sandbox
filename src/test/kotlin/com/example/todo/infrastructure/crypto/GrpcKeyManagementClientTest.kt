package com.example.todo.infrastructure.crypto

import com.example.todo.domain.crypto.CryptoException
import com.example.todo.domain.crypto.CryptoKeyNotFoundException
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ServerCalls
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class GrpcKeyManagementClientTest {

    private lateinit var server: Server
    private lateinit var client: GrpcKeyManagementClient
    private val serverName = InProcessServerBuilder.generateName()

    @BeforeEach
    fun setUp() {
        val serviceDef = ServerServiceDefinition.builder("com.example.crypto.v1.KeyManagementService")
            .addMethod(
                GrpcKeyManagementClient.ENCRYPT_METHOD,
                ServerCalls.asyncUnaryCall { request, responseObserver ->
                    when (request.keyName) {
                        "projects/todo/keys/task-key" -> {
                            val rawText = String(request.plaintext, StandardCharsets.UTF_8)
                            val dummyEncrypted = "ENCRYPTED_$rawText".toByteArray()
                            responseObserver.onNext(
                                GrpcKeyManagementClient.EncryptResponse(request.keyName, dummyEncrypted),
                            )
                            responseObserver.onCompleted()
                        }
                        "projects/todo/keys/invalid-key" -> {
                            responseObserver.onError(
                                Status.NOT_FOUND.withDescription("Key not found: invalid-key").asRuntimeException(),
                            )
                        }
                        else -> {
                            responseObserver.onError(
                                Status.INTERNAL.withDescription("Internal KMS failure").asRuntimeException(),
                            )
                        }
                    }
                },
            )
            .addMethod(
                GrpcKeyManagementClient.DECRYPT_METHOD,
                ServerCalls.asyncUnaryCall { request, responseObserver ->
                    when (request.keyName) {
                        "projects/todo/keys/task-key" -> {
                            val rawCipher = String(request.ciphertext, StandardCharsets.UTF_8)
                            val plain = rawCipher.removePrefix("ENCRYPTED_").toByteArray()
                            responseObserver.onNext(
                                GrpcKeyManagementClient.DecryptResponse(request.keyName, plain),
                            )
                            responseObserver.onCompleted()
                        }
                        else -> {
                            responseObserver.onError(
                                Status.NOT_FOUND.withDescription("Key not found").asRuntimeException(),
                            )
                        }
                    }
                },
            )
            .build()

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceDef)
            .build()
            .start()

        val channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        client = GrpcKeyManagementClient(channel)
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.shutdownNow()
    }

    @Test
    fun `encrypt successfully returns encrypted ciphertext from KMS`() {
        val plaintext = "Secret Todo Memo".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = client.encrypt("projects/todo/keys/task-key", plaintext)

        assertEquals("ENCRYPTED_Secret Todo Memo", String(ciphertext, StandardCharsets.UTF_8))
    }

    @Test
    fun `decrypt successfully returns decrypted plaintext from KMS`() {
        val ciphertext = "ENCRYPTED_Secret Todo Memo".toByteArray(StandardCharsets.UTF_8)
        val plaintext = client.decrypt("projects/todo/keys/task-key", ciphertext)

        assertEquals("Secret Todo Memo", String(plaintext, StandardCharsets.UTF_8))
    }

    @Test
    fun `encrypt throws CryptoKeyNotFoundException when key does not exist`() {
        val plaintext = "Hello".toByteArray(StandardCharsets.UTF_8)

        val ex = assertThrows(CryptoKeyNotFoundException::class.java) {
            client.encrypt("projects/todo/keys/invalid-key", plaintext)
        }
        assertEquals(true, ex.message?.contains("Key not found: invalid-key"))
    }

    @Test
    fun `encrypt throws CryptoException on server error`() {
        val plaintext = "Hello".toByteArray(StandardCharsets.UTF_8)

        val ex = assertThrows(CryptoException::class.java) {
            client.encrypt("projects/todo/keys/error-key", plaintext)
        }
        assertEquals(true, ex.message?.contains("INTERNAL"))
    }

    @Test
    fun `equals and hashCode for internal data classes work correctly`() {
        val req1 = GrpcKeyManagementClient.EncryptRequest("k1", byteArrayOf(1, 2))
        val req2 = GrpcKeyManagementClient.EncryptRequest("k1", byteArrayOf(1, 2))
        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())

        val res1 = GrpcKeyManagementClient.EncryptResponse("k1", byteArrayOf(1, 2))
        val res2 = GrpcKeyManagementClient.EncryptResponse("k1", byteArrayOf(1, 2))
        assertEquals(res1, res2)
        assertEquals(res1.hashCode(), res2.hashCode())

        val decReq1 = GrpcKeyManagementClient.DecryptRequest("k1", byteArrayOf(1, 2))
        val decReq2 = GrpcKeyManagementClient.DecryptRequest("k1", byteArrayOf(1, 2))
        assertEquals(decReq1, decReq2)
        assertEquals(decReq1.hashCode(), decReq2.hashCode())

        val decRes1 = GrpcKeyManagementClient.DecryptResponse("k1", byteArrayOf(1, 2))
        val decRes2 = GrpcKeyManagementClient.DecryptResponse("k1", byteArrayOf(1, 2))
        assertEquals(decRes1, decRes2)
        assertEquals(decRes1.hashCode(), decRes2.hashCode())
    }
}
