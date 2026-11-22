package com.example.grpc.common

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class InternalApiKeyGrpcInterceptor(
    private val expectedApiKey: String,
) : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val providedKey = headers.get(INTERNAL_API_KEY_METADATA_KEY)
        return when {
            providedKey.isNullOrBlank() -> {
                call.close(
                    Status.UNAUTHENTICATED.withDescription("Missing $INTERNAL_API_KEY_HEADER metadata"),
                    Metadata(),
                )
                object : ServerCall.Listener<ReqT>() {}
            }
            !constantTimeEquals(providedKey, expectedApiKey) -> {
                call.close(
                    Status.PERMISSION_DENIED.withDescription("Invalid $INTERNAL_API_KEY_HEADER metadata"),
                    Metadata(),
                )
                object : ServerCall.Listener<ReqT>() {}
            }
            else -> next.startCall(call, headers)
        }
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean =
        MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )

    companion object {
        const val INTERNAL_API_KEY_HEADER = "x-internal-api-key"
        val INTERNAL_API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of(INTERNAL_API_KEY_HEADER, Metadata.ASCII_STRING_MARSHALLER)
    }
}
