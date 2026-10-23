package com.example.todo.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataUserRepository : JpaRepository<UserJpaEntity, UUID> {
    fun findByUsername(username: String): UserJpaEntity?

    fun existsByUsername(username: String): Boolean
}

