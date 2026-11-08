package com.example.todo.sideeffect

import com.google.cloud.spanner.Mutation
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterDeleteEvent
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterExecuteDmlEvent
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterSaveEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Spring Data Cloud Spanner のライフサイクルイベントを利用して、アプリケーションが発行した
 * データベースへの副作用（INSERT / UPDATE / DELETE / UPSERT）のみを記録するインスペクター。
 *
 * SELECT などの読み取り操作は副作用がないため記録されません。
 */
internal object DatabaseSideEffectInspector {
    private val recordedMutations = ConcurrentLinkedQueue<DatabaseMutation>()

    @Volatile
    private var tracking: Boolean = false

    fun startTracking() {
        recordedMutations.clear()
        tracking = true
    }

    fun stopTracking() {
        tracking = false
    }

    fun isTracking(): Boolean = tracking

    fun <T> withoutTracking(block: () -> T): T {
        val previous = tracking
        tracking = false
        return try {
            block()
        } finally {
            tracking = previous
        }
    }

    fun clear() {
        recordedMutations.clear()
    }

    fun getMutations(): List<DatabaseMutation> = recordedMutations.toList()

    fun recordMutation(mutation: DatabaseMutation) {
        if (tracking) {
            recordedMutations.add(mutation)
        }
    }

    fun inspect(sql: String?): String? {
        if (!tracking || sql == null) return sql

        val cleanSql = stripComments(sql.trim())
        val firstWord = cleanSql.split(Regex("\\s+"), limit = 2).firstOrNull()?.uppercase()

        val operation = when (firstWord) {
            "INSERT" -> DbOperation.INSERT
            "UPDATE" -> DbOperation.UPDATE
            "DELETE" -> DbOperation.DELETE
            else -> null
        }

        if (operation != null) {
            val table = extractTable(operation, cleanSql)
            recordMutation(DatabaseMutation(operation, table, cleanSql))
        }

        return sql
    }

    private fun stripComments(sql: String): String {
        var result = sql
        while (result.startsWith("/*")) {
            val endIndex = result.indexOf("*/")
            result = if (endIndex != -1) {
                result.substring(endIndex + 2).trimStart()
            } else {
                break
            }
        }
        return result
    }

    private fun extractTable(operation: DbOperation, sql: String): String {
        val tokens = sql.split(Regex("\\s+")).map { it.trim('`', '"', '\'', ';', '(', ')') }
        val target = when (operation) {
            DbOperation.INSERT -> {
                val intoIndex = tokens.indexOfFirst { it.equals("into", ignoreCase = true) }
                if (intoIndex != -1 && intoIndex + 1 < tokens.size) tokens[intoIndex + 1] else "unknown"
            }
            DbOperation.UPDATE, DbOperation.UPSERT -> {
                val updateIndex = tokens.indexOfFirst { it.equals("update", ignoreCase = true) }
                if (updateIndex != -1 && updateIndex + 1 < tokens.size) tokens[updateIndex + 1] else "unknown"
            }
            DbOperation.DELETE -> {
                val fromIndex = tokens.indexOfFirst { it.equals("from", ignoreCase = true) }
                if (fromIndex != -1 && fromIndex + 1 < tokens.size) tokens[fromIndex + 1] else "unknown"
            }
        }
        return target.lowercase()
    }
}

/**
 * Spring Data Spanner のイベントを捕捉して DatabaseSideEffectInspector に記録するリスナー。
 */
@Component
internal class SpannerDatabaseSideEffectListener {

    @EventListener
    fun onAfterSave(event: AfterSaveEvent) {
        if (!DatabaseSideEffectInspector.isTracking()) return
        event.mutations.forEach { mutation ->
            val op = when (mutation.operation) {
                Mutation.Op.INSERT -> DbOperation.INSERT
                Mutation.Op.UPDATE -> DbOperation.UPDATE
                Mutation.Op.INSERT_OR_UPDATE -> DbOperation.UPSERT
                Mutation.Op.REPLACE -> DbOperation.UPDATE
                Mutation.Op.DELETE -> DbOperation.DELETE
                else -> null
            }
            if (op != null) {
                DatabaseSideEffectInspector.recordMutation(
                    DatabaseMutation(
                        operation = op,
                        table = mutation.table.lowercase(),
                        sql = "Mutation: ${mutation.operation} on ${mutation.table}",
                    ),
                )
            }
        }
    }

    @EventListener
    fun onAfterDelete(event: AfterDeleteEvent) {
        if (!DatabaseSideEffectInspector.isTracking()) return
        event.mutations.forEach { mutation ->
            DatabaseSideEffectInspector.recordMutation(
                DatabaseMutation(
                    operation = DbOperation.DELETE,
                    table = mutation.table.lowercase(),
                    sql = "Mutation: DELETE on ${mutation.table}",
                ),
            )
        }
    }

    @EventListener
    fun onAfterExecuteDml(event: AfterExecuteDmlEvent) {
        if (!DatabaseSideEffectInspector.isTracking()) return
        DatabaseSideEffectInspector.inspect(event.statement.sql)
    }
}
