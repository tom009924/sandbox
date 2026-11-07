package com.example.todo.sideeffect

/**
 * データベース更新操作の種別（参照である SELECT は対象外）。
 */
internal enum class DbOperation {
    INSERT,
    UPDATE,
    DELETE,
}

/**
 * データベースへの副作用（更新操作）を表すデータクラス。
 */
internal data class DatabaseMutation(
    val operation: DbOperation,
    val table: String,
    val sql: String,
)
