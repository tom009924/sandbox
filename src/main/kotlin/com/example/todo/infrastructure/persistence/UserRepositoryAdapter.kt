package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UserRepositoryAdapter(
    private val springDataUserRepository: SpringDataUserRepository,
    private val spannerTemplate: SpannerTemplate,
) : UserRepository {
    override fun findById(id: UUID): User? =
        springDataUserRepository.findById(id.toString()).map { it.toDomain() }.orElse(null)

    override fun findAll(): List<User> =
        springDataUserRepository.findAll().map { it.toDomain() }

    override fun findByUsername(username: String): User? =
        springDataUserRepository.findByUsername(username)?.toDomain()

    override fun existsByUsername(username: String): Boolean =
        springDataUserRepository.existsByUsername(username)

    override fun save(user: User): User {
        val isNew = user.id == null
        val id = user.id ?: UUID.randomUUID()
        val entity = user.copy(id = id).toEntity()
        if (isNew) {
            spannerTemplate.insert(entity)
        } else {
            spannerTemplate.update(entity)
        }
        return entity.toDomain()
    }

    override fun count(): Long = springDataUserRepository.count()

    override fun deleteAll() {
        springDataUserRepository.deleteAll()
    }

    private fun UserSpannerEntity.toDomain(): User =
        User(
            id = UUID.fromString(id),
            username = username,
            passwordHash = passwordHash,
        )

    private fun User.toEntity(): UserSpannerEntity =
        UserSpannerEntity(
            id = (id ?: UUID.randomUUID()).toString(),
            username = username,
            passwordHash = passwordHash,
        )
}
