package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UserRepositoryAdapter(
    private val springDataUserRepository: SpringDataUserRepository,
) : UserRepository {
    override fun findById(id: UUID): User? =
        springDataUserRepository.findById(id).map { it.toDomain() }.orElse(null)

    override fun findAll(): List<User> =
        springDataUserRepository.findAll().map { it.toDomain() }

    override fun findByUsername(username: String): User? =
        springDataUserRepository.findByUsername(username)?.toDomain()

    override fun existsByUsername(username: String): Boolean =
        springDataUserRepository.existsByUsername(username)

    override fun save(user: User): User {
        val entity = user.toEntity()
        val saved = springDataUserRepository.save(entity)
        return saved.toDomain()
    }

    override fun count(): Long = springDataUserRepository.count()

    override fun deleteAll() {
        springDataUserRepository.deleteAll()
    }

    private fun UserJpaEntity.toDomain(): User =
        User(
            id = id,
            username = username,
            passwordHash = passwordHash,
        )

    private fun User.toEntity(): UserJpaEntity =
        UserJpaEntity(
            id = id,
            username = username,
            passwordHash = passwordHash,
        )
}
