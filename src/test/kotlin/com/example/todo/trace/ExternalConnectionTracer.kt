package com.example.todo.trace

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

enum class ExternalSystem(val alias: String, val label: String) {
    SPANNER("Spanner", "Cloud Spanner"),
    KMS("KMS", "KMS (gRPC)"),
    PUBSUB("PubSub", "Cloud Pub/Sub"),
    EMAIL("Email", "Email API (HTTP)"),
}

data class ExternalCallEvent(
    val system: ExternalSystem,
    val operation: String,
    val async: Boolean = false,
)

data class EndpointTrace(
    val endpoint: String,
    val transactionalReadOnly: Boolean?,
    val events: List<ExternalCallEvent>,
)

class ExternalConnectionTracer {
    private val activeEvents = ThreadLocal<MutableList<ExternalCallEvent>?>()
    private val recordedTraces = mutableListOf<EndpointTrace>()

    fun traces(): List<EndpointTrace> = recordedTraces.toList()

    fun <T> trace(
        endpoint: String,
        transactionalReadOnly: Boolean? = null,
        block: () -> T,
    ): T {
        val events = mutableListOf<ExternalCallEvent>()
        val previous = activeEvents.get()
        activeEvents.set(events)
        try {
            return block()
        } finally {
            activeEvents.set(previous)
            recordedTraces.add(
                EndpointTrace(
                    endpoint = endpoint,
                    transactionalReadOnly = transactionalReadOnly,
                    events = events.toList(),
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> wrap(
        interfaceType: Class<T>,
        target: T,
        system: ExternalSystem,
        async: Boolean = false,
        operationFormatter: (Method, Array<out Any?>?) -> String = { method, _ -> method.name },
    ): T = Proxy.newProxyInstance(
        interfaceType.classLoader,
        arrayOf(interfaceType),
    ) { _, method, args ->
        if (method.declaringClass == Any::class.java) {
            invokeTarget(method, target, args)
        } else {
            activeEvents.get()?.add(
                ExternalCallEvent(
                    system = system,
                    operation = operationFormatter(method, args),
                    async = async,
                ),
            )
            invokeTarget(method, target, args)
        }
    } as T

    fun renderMermaid(trace: EndpointTrace): String {
        val participants = trace.events.map { it.system }.distinct()
        return buildString {
            appendLine("```mermaid")
            appendLine("sequenceDiagram")
            appendLine("    autonumber")
            appendLine("    participant Client")
            appendLine("    participant App as Todo App")
            participants.forEach { system ->
                appendLine("    participant ${system.alias} as ${system.label}")
            }
            appendLine("    Client->>App: ${trace.endpoint}")
            trace.events.forEach { event ->
                val arrow = if (event.async) "-->>" else "->>"
                appendLine("    App$arrow${event.system.alias}: ${event.operation}")
            }
            if (trace.transactionalReadOnly == false && participants.contains(ExternalSystem.SPANNER)) {
                appendLine("    App->>${ExternalSystem.SPANNER.alias}: Commit (@Transactional)")
            }
            appendLine("    App-->>Client: Response")
            append("```")
        }
    }

    fun renderMarkdownReport(traces: List<EndpointTrace> = recordedTraces): String = buildString {
        appendLine("# 外部接続順ダイアグラム（動的トレース自動生成）")
        appendLine()
        appendLine(
            "> **自動生成ドキュメント**: 本ドキュメントは `./gradlew generateExternalConnectionDiagrams` " +
                "により動的トレースから自動生成されています。手動編集は行わないでください。",
        )
        appendLine()
        appendLine("| エンドポイント | トランザクション | 外部接続順 |")
        appendLine("|---|---|---|")
        traces.forEach { trace ->
            val txLabel = when (trace.transactionalReadOnly) {
                true -> "readOnly"
                false -> "readWrite"
                null -> "-"
            }
            val flow = if (trace.events.isEmpty()) {
                "(外部接続なし)"
            } else {
                trace.events.joinToString(" → ") { "${it.system.alias}.${it.operation}" }
            }
            appendLine("| `${trace.endpoint}` | $txLabel | $flow |")
        }
        appendLine()
        traces.forEach { trace ->
            appendLine("## ${trace.endpoint}")
            appendLine()
            appendLine(renderMermaid(trace))
            appendLine()
        }
    }

    fun writeReport(
        outputPath: Path,
        traces: List<EndpointTrace> = recordedTraces,
    ) {
        outputPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(outputPath, renderMarkdownReport(traces))
    }

    private fun invokeTarget(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
}
