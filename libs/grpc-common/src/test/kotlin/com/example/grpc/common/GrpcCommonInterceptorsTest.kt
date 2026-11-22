package com.example.grpc.common

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GrpcCommonInterceptorsTest {

    @Test
    fun `InternalApiKeyGrpcInterceptor rejects missing or blank key with UNAUTHENTICATED`() {
        val interceptor = InternalApiKeyGrpcInterceptor("secret-key")
        val nextCalled = AtomicBoolean(false)
        val next = ServerCallHandler<String, String> { _, _ ->
            nextCalled.set(true)
            object : ServerCall.Listener<String>() {}
        }

        val missingCall = CapturingServerCall()
        interceptor.interceptCall(missingCall, Metadata(), next)
        assertFalse(nextCalled.get())
        assertEquals(Status.Code.UNAUTHENTICATED, missingCall.closedStatus?.code)

        val blankCall = CapturingServerCall()
        val blankHeaders = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, "   ")
        }
        interceptor.interceptCall(blankCall, blankHeaders, next)
        assertFalse(nextCalled.get())
        assertEquals(Status.Code.UNAUTHENTICATED, blankCall.closedStatus?.code)
    }

    @Test
    fun `InternalApiKeyGrpcInterceptor rejects invalid key with PERMISSION_DENIED and allows valid key`() {
        val interceptor = InternalApiKeyGrpcInterceptor("secret-key")
        val nextCalled = AtomicBoolean(false)
        val next = ServerCallHandler<String, String> { _, _ ->
            nextCalled.set(true)
            object : ServerCall.Listener<String>() {}
        }

        val badCall = CapturingServerCall()
        val badHeaders = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, "wrong-key")
        }
        interceptor.interceptCall(badCall, badHeaders, next)
        assertFalse(nextCalled.get())
        assertEquals(Status.Code.PERMISSION_DENIED, badCall.closedStatus?.code)

        val validCall = CapturingServerCall()
        val validHeaders = Metadata().apply {
            put(InternalApiKeyGrpcInterceptor.INTERNAL_API_KEY_METADATA_KEY, "secret-key")
        }
        interceptor.interceptCall(validCall, validHeaders, next)
        assertTrue(nextCalled.get())
        assertNull(validCall.closedStatus)
    }

    @Test
    fun `TracingGrpcClientInterceptor and TracingGrpcServerInterceptor propagate W3C trace context`() {
        val clientInterceptor = TracingGrpcClientInterceptor()
        val serverInterceptor = TracingGrpcServerInterceptor()
        val capturedHeaders = AtomicReference<Metadata>()
        val capturedServerTraceId = AtomicReference<String>()
        val capturedServerSpanId = AtomicReference<String>()

        val fakeChannel = object : Channel() {
            override fun <ReqT, RespT> newCall(
                methodDescriptor: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
            ): ClientCall<ReqT, RespT> = object : ClientCall<ReqT, RespT>() {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    capturedHeaders.set(headers)
                }

                override fun request(numMessages: Int) = Unit
                override fun cancel(message: String?, cause: Throwable?) = Unit
                override fun halfClose() = Unit
                override fun sendMessage(message: ReqT) = Unit
            }

            override fun authority(): String = "localhost"
        }

        val spanContext = SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            TraceState.getDefault(),
        )
        Context.root().with(Span.wrap(spanContext)).makeCurrent().use {
            val call = clientInterceptor.interceptCall(ECHO_METHOD, CallOptions.DEFAULT, fakeChannel)
            call.start(object : ClientCall.Listener<String>() {}, Metadata())
        }

        val headers = capturedHeaders.get()
        assertNotNull(headers.get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)))

        val events = mutableListOf<String>()
        val listener = serverInterceptor.interceptCall(
            CapturingServerCall(),
            headers,
        ) { _, _ ->
            val currentSpan = Span.current().spanContext
            capturedServerTraceId.set(currentSpan.traceId)
            capturedServerSpanId.set(currentSpan.spanId)
            object : ServerCall.Listener<String>() {
                override fun onMessage(message: String) {
                    events.add("onMessage:${Span.current().spanContext.traceId}:$message")
                }

                override fun onHalfClose() {
                    events.add("onHalfClose:${Span.current().spanContext.traceId}")
                }

                override fun onCancel() {
                    events.add("onCancel:${Span.current().spanContext.traceId}")
                }

                override fun onComplete() {
                    events.add("onComplete:${Span.current().spanContext.traceId}")
                }

                override fun onReady() {
                    events.add("onReady:${Span.current().spanContext.traceId}")
                }
            }
        }

        listener.onReady()
        listener.onMessage("msg")
        listener.onHalfClose()
        listener.onComplete()
        listener.onCancel()

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", capturedServerTraceId.get())
        assertEquals("00f067aa0ba902b7", capturedServerSpanId.get())
        assertEquals(5, events.size)
        assertTrue(events.all { it.contains("4bf92f3577b34da6a3ce929d0e0e4736") })
    }

    private class CapturingServerCall : ServerCall<String, String>() {
        var closedStatus: Status? = null
        override fun request(numMessages: Int) = Unit
        override fun sendHeaders(headers: Metadata) = Unit
        override fun sendMessage(message: String) = Unit
        override fun close(status: Status, trailers: Metadata) {
            closedStatus = status
        }

        override fun isCancelled(): Boolean = false
        override fun getMethodDescriptor(): MethodDescriptor<String, String> = ECHO_METHOD
    }

    private companion object {
        val STRING_MARSHALLER = object : MethodDescriptor.Marshaller<String> {
            override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray())
            override fun parse(stream: InputStream): String = stream.readBytes().decodeToString()
        }

        val ECHO_METHOD: MethodDescriptor<String, String> = MethodDescriptor.newBuilder<String, String>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.EchoService/Echo")
            .setRequestMarshaller(STRING_MARSHALLER)
            .setResponseMarshaller(STRING_MARSHALLER)
            .build()
    }
}
