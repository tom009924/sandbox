package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import org.springframework.stereotype.Repository

@Repository
interface SpringDataUserRepository : SpannerRepository<UserSpannerEntity, String> {
    fun findByUsername(username: String): UserSpannerEntity?

    fun existsByUsername(username: String): Boolean
}
