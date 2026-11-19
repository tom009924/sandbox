package com.example.identity.infrastructure.persistence

import com.example.identity.domain.model.TodoPriority
import com.example.identity.domain.model.UserSettings
import com.example.identity.domain.repository.UserSettingsRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserSettingsRepositoryAdapter(
    private val springDataUserSettingsRepository: SpringDataUserSettingsRepository,
    private val spannerTemplate: SpannerTemplate,
) : UserSettingsRepository {
    override fun findByUserId(userId: UUID): UserSettings? =
        springDataUserSettingsRepository.findById(userId.toString()).map { it.toDomain() }.orElse(null)

    override fun save(userSettings: UserSettings): UserSettings {
        val entity = userSettings.toEntity()
        spannerTemplate.upsert(entity)
        return entity.toDomain()
    }

    private fun UserSettingsSpannerEntity.toDomain() = UserSettings(
        userId = UUID.fromString(userId),
        defaultPriority = TodoPriority.valueOf(defaultPriority),
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )

    private fun UserSettings.toEntity() = UserSettingsSpannerEntity(
        userId = userId.toString(),
        defaultPriority = defaultPriority.name,
        defaultEstimatedMinutes = defaultEstimatedMinutes,
        maxActiveTodos = maxActiveTodos,
    )
}
