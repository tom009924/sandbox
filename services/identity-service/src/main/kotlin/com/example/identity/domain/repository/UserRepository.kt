package com.example.identity.domain.repository

import com.example.identity.domain.model.User
import java.util.UUID

interface UserRepository {
    fun findById(id: UUID): User?

    fun findAllByIdIn(ids: Collection<UUID>): List<User>

    fun findAll(): List<User>

    fun findByUsername(username: String): User?

    fun existsByUsername(username: String): Boolean

    fun save(user: User): User

    fun count(): Long

    fun deleteAll()
}
