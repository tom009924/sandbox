package com.example.todo.sideeffect.proxy

import com.example.todo.sideeffect.DatabaseMutation
import com.example.todo.sideeffect.DbOperation
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Spanner に対するデータベース更新（INSERT / UPDATE / DELETE / UPSERT）を
 * スレッドセーフに蓄積し、検証（verify）および未アサーションの検知を行う Spanner 専用レジストリ。
 */
internal class SpannerSideEffectRegistry(
    initialAllowedTables: Set<String> = emptySet(),
) {
    private val recordedMutations = ConcurrentLinkedQueue<DatabaseMutation>()
    private val verifiedMutations = ConcurrentLinkedQueue<DatabaseMutation>()
    private val allowedTables = ConcurrentLinkedQueue(initialAllowedTables.map { it.lowercase() })

    @Volatile
    private var tracking: Boolean = false

    fun startTracking() {
        recordedMutations.clear()
        verifiedMutations.clear()
        tracking = true
    }

    fun stopTracking() {
        tracking = false
    }

    fun isTracking(): Boolean = tracking

    fun recordMutation(mutation: DatabaseMutation) {
        if (tracking) {
            recordedMutations.add(mutation)
        }
    }

    fun allowTable(table: String) {
        allowedTables.add(table.lowercase())
    }

    fun verify(operation: DbOperation, table: String, count: Int = 1): VerificationResult {
        val targetTable = table.lowercase()
        val unverified = recordedMutations.filter { it !in verifiedMutations }
        val matching = unverified.filter { mutation ->
            val matchesOp = mutation.operation == operation ||
                (mutation.operation == DbOperation.UPSERT &&
                    (operation == DbOperation.INSERT || operation == DbOperation.UPDATE))
            matchesOp && mutation.table == targetTable
        }

        if (matching.size != count) {
            return VerificationResult(
                success = false,
                message = "Expected $count $operation on table '$targetTable', found ${matching.size}. " +
                    "Unverified: $unverified",
            )
        }

        verifiedMutations.addAll(matching.take(count))
        return VerificationResult(success = true)
    }

    fun getUnverifiedMutations(): List<DatabaseMutation> {
        return recordedMutations.filter {
            it !in verifiedMutations && it.table.lowercase() !in allowedTables
        }
    }
}

internal data class VerificationResult(
    val success: Boolean,
    val message: String = "",
)

internal data class UnverifiedSpannerSummary(
    val mutations: List<DatabaseMutation>,
) {
    val isEmpty: Boolean get() = mutations.isEmpty()

    fun toErrorMessage(): String {
        return "Unverified Spanner database mutations detected (${mutations.size}): " +
            mutations.joinToString { "${it.operation} on ${it.table}" }
    }
}
