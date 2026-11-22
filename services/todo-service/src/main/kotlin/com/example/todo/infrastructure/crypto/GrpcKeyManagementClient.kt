package com.example.todo.infrastructure.crypto

import com.example.todo.domain.crypto.CryptoException
import com.example.todo.domain.crypto.CryptoKeyNotFoundException
import com.example.todo.domain.crypto.CryptoService
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ClientCalls
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GrpcKeyManagementClient(
    private val channel: Channel,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : CryptoService, AutoCloseable {

    constructor(properties: GrpcKeyManagementProperties) : this(
        ManagedChannelBuilder.forAddress(properties.host, properties.port)
            .usePlaintext()
            .build(),
        properties.timeout.toMillis(),
    )

    override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray {
        val request = EncryptRequest(keyName, plaintext)
        try {
            val callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
            val response = ClientCalls.blockingUnaryCall(channel, ENCRYPT_METHOD, callOptions, request)
            return response.ciphertext
        } catch (e: StatusRuntimeException) {
            handleGrpcException(e, keyName)
        }
    }

    override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray {
        val request = DecryptRequest(keyName, ciphertext)
        try {
            val callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
            val response = ClientCalls.blockingUnaryCall(channel, DECRYPT_METHOD, callOptions, request)
            return response.plaintext
        } catch (e: StatusRuntimeException) {
            handleGrpcException(e, keyName)
        }
    }

    override fun close() {
        if (channel is ManagedChannel) {
            channel.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun handleGrpcException(e: StatusRuntimeException, keyName: String): Nothing {
        when (e.status.code) {
            Status.Code.NOT_FOUND -> throw CryptoKeyNotFoundException(
                "Key not found: $keyName (${e.status.description})",
                e,
            )
            else -> throw CryptoException(
                "KMS gRPC request failed: ${e.status.code} - ${e.status.description}",
                e,
            )
        }
    }

    internal data class EncryptRequest(val keyName: String, val plaintext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptRequest) return false
            return keyName == other.keyName && plaintext.contentEquals(other.plaintext)
        }

        override fun hashCode(): Int = 31 * keyName.hashCode() + plaintext.contentHashCode()
    }

    internal data class EncryptResponse(val keyName: String, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptResponse) return false
            return keyName == other.keyName && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int = 31 * keyName.hashCode() + ciphertext.contentHashCode()
    }

    internal data class DecryptRequest(val keyName: String, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptRequest) return false
            return keyName == other.keyName && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int = 31 * keyName.hashCode() + ciphertext.contentHashCode()
    }

    internal data class DecryptResponse(val keyName: String, val plaintext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptResponse) return false
            return keyName == other.keyName && plaintext.contentEquals(other.plaintext)
        }

        override fun hashCode(): Int = 31 * keyName.hashCode() + plaintext.contentHashCode()
    }

    companion object {
        private const val SERVICE_NAME = "com.example.crypto.v1.KeyManagementService"
        private const val DEFAULT_TIMEOUT_MILLIS = 5000L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val FIELD_KEY_NAME = 1
        private const val FIELD_DATA = 2

        internal val ENCRYPT_METHOD: MethodDescriptor<EncryptRequest, EncryptResponse> =
            MethodDescriptor.newBuilder<EncryptRequest, EncryptResponse>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Encrypt"))
                .setRequestMarshaller(object : MethodDescriptor.Marshaller<EncryptRequest> {
                    override fun stream(value: EncryptRequest): InputStream =
                        ByteArrayInputStream(encodeKeyAndBytes(value.keyName, value.plaintext))

                    override fun parse(stream: InputStream): EncryptRequest {
                        val (keyName, bytes) = decodeKeyAndBytes(stream.readAllBytes())
                        return EncryptRequest(keyName, bytes)
                    }
                })
                .setResponseMarshaller(object : MethodDescriptor.Marshaller<EncryptResponse> {
                    override fun stream(value: EncryptResponse): InputStream =
                        ByteArrayInputStream(encodeKeyAndBytes(value.keyName, value.ciphertext))

                    override fun parse(stream: InputStream): EncryptResponse {
                        val (keyName, bytes) = decodeKeyAndBytes(stream.readAllBytes())
                        return EncryptResponse(keyName, bytes)
                    }
                })
                .build()

        internal val DECRYPT_METHOD: MethodDescriptor<DecryptRequest, DecryptResponse> =
            MethodDescriptor.newBuilder<DecryptRequest, DecryptResponse>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Decrypt"))
                .setRequestMarshaller(object : MethodDescriptor.Marshaller<DecryptRequest> {
                    override fun stream(value: DecryptRequest): InputStream =
                        ByteArrayInputStream(encodeKeyAndBytes(value.keyName, value.ciphertext))

                    override fun parse(stream: InputStream): DecryptRequest {
                        val (keyName, bytes) = decodeKeyAndBytes(stream.readAllBytes())
                        return DecryptRequest(keyName, bytes)
                    }
                })
                .setResponseMarshaller(object : MethodDescriptor.Marshaller<DecryptResponse> {
                    override fun stream(value: DecryptResponse): InputStream =
                        ByteArrayInputStream(encodeKeyAndBytes(value.keyName, value.plaintext))

                    override fun parse(stream: InputStream): DecryptResponse {
                        val (keyName, bytes) = decodeKeyAndBytes(stream.readAllBytes())
                        return DecryptResponse(keyName, bytes)
                    }
                })
                .build()

        private fun encodeKeyAndBytes(keyName: String, data: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream()
            val cos = CodedOutputStream.newInstance(baos)
            cos.writeString(FIELD_KEY_NAME, keyName)
            cos.writeByteArray(FIELD_DATA, data)
            cos.flush()
            return baos.toByteArray()
        }

        private fun decodeKeyAndBytes(bytes: ByteArray): Pair<String, ByteArray> {
            val cis = CodedInputStream.newInstance(bytes)
            var keyName = ""
            var data = ByteArray(0)
            while (!cis.isAtEnd) {
                val tag = cis.readTag()
                when (WireFormat.getTagFieldNumber(tag)) {
                    FIELD_KEY_NAME -> keyName = cis.readString()
                    FIELD_DATA -> data = cis.readByteArray()
                    else -> cis.skipField(tag)
                }
            }
            return Pair(keyName, data)
        }
    }
}
