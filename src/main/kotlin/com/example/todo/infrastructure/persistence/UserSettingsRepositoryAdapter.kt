package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.UserSettingsRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserSettingsRepositoryAdapter(
    private val springDataUserSettingsRepository: SpringDataUserSettingsRepository,
) : UserSettingsRepository {
    override fun findByUserId(userId: UUID): UserSettings? =
        springDataUserSettingsRepository.findById(userId).map { it.toDomain() }.orElse(null)

    override fun save(userSettings: UserSettings): UserSettings {
        val entity = userSettings.toEntity()
        val saved = springDataUserSettingsRepository.save(entity)
        return saved.toDomain()
    }

    private fun UserSettingsJpaEntity.toDomain() = UserSettings(
        userId = userId,
        defaultPriority = defaultPriority,
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )

    private fun UserSettings.toEntity() = UserSettingsJpaEntity(
        userId = userId,
        defaultPriority = defaultPriority,
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )
}

