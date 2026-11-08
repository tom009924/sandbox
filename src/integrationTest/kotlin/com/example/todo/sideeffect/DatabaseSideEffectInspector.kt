package com.example.todo.sideeffect

import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hibernate の StatementInspector を利用して、アプリケーションが発行した
 * データベースへの副作用（INSERT / UPDATE / DELETE）のみを記録するインスペクター。
 *
 * SELECT などの読み取り操作は副作用がないため完全に無視されます。
 */
internal object DatabaseSideEffectInspector : StatementInspector {
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

    override fun inspect(sql: String?): String? {
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
            recordedMutations.add(DatabaseMutation(operation, table, cleanSql))
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
            DbOperation.UPDATE -> {
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
