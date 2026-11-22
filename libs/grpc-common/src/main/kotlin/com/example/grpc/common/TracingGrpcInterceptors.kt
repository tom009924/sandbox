package com.example.grpc.common

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

class TracingGrpcClientInterceptor : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val delegate = next.newCall(method, callOptions)
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                W3CTraceContextPropagator.getInstance().inject(
                    Context.current(),
                    headers,
                    MetadataSetter,
                )
                super.start(responseListener, headers)
            }
        }
    }

    private object MetadataSetter : TextMapSetter<Metadata> {
        override fun set(carrier: Metadata?, key: String, value: String) {
            if (carrier == null) return
            val metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
            carrier.put(metadataKey, value)
        }
    }
}

class TracingGrpcServerInterceptor : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val extractedContext = W3CTraceContextPropagator.getInstance().extract(
            Context.root(),
            headers,
            MetadataGetter,
        )
        val scope = extractedContext.makeCurrent()
        val delegate = try {
            next.startCall(call, headers)
        } finally {
            scope.close()
        }
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
            override fun onMessage(message: ReqT) {
                extractedContext.makeCurrent().use { super.onMessage(message) }
            }

            override fun onHalfClose() {
                extractedContext.makeCurrent().use { super.onHalfClose() }
            }

            override fun onCancel() {
                extractedContext.makeCurrent().use { super.onCancel() }
            }

            override fun onComplete() {
                extractedContext.makeCurrent().use { super.onComplete() }
            }

            override fun onReady() {
                extractedContext.makeCurrent().use { super.onReady() }
            }
        }
    }

    private object MetadataGetter : TextMapGetter<Metadata> {
        override fun keys(carrier: Metadata): Iterable<String> = carrier.keys()

        override fun get(carrier: Metadata?, key: String): String? {
            if (carrier == null) return null
            val metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
            return carrier.get(metadataKey)
        }
    }
}
