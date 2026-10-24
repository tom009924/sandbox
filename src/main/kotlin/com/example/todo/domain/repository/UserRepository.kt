package com.example.todo.domain.repository

import com.example.todo.domain.model.User
import java.util.UUID

interface UserRepository {
    fun findById(id: UUID): User?

    fun findByUsername(username: String): User?

    fun existsByUsername(username: String): Boolean

    fun save(user: User): User

    fun count(): Long

    fun deleteAll()
}

