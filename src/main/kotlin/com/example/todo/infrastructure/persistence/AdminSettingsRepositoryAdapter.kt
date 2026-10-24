package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.AdminSettings
import com.example.todo.domain.repository.AdminSettingsRepository
import org.springframework.stereotype.Component

@Component
class AdminSettingsRepositoryAdapter(
    private val springDataAdminSettingsRepository: SpringDataAdminSettingsRepository,
) : AdminSettingsRepository {

    override fun find(): AdminSettings? =
        springDataAdminSettingsRepository.findById(AdminSettingsJpaEntity.GLOBAL_ID)
            .map { it.toDomain() }
            .orElse(null)

    override fun save(settings: AdminSettings): AdminSettings {
        val entity = settings.toEntity()
        val saved = springDataAdminSettingsRepository.save(entity)
        return saved.toDomain()
    }

    override fun deleteAll() {
        springDataAdminSettingsRepository.deleteAll()
    }

    private fun AdminSettingsJpaEntity.toDomain(): AdminSettings =
        AdminSettings(
            maxAllowedActiveTodos = maxAllowedActiveTodos,
            allowUserRegistration = allowUserRegistration,
            defaultPriority = defaultPriority,
            defaultEstimatedMinutes = defaultEstimatedMinutes,
        )

    private fun AdminSettings.toEntity(): AdminSettingsJpaEntity =
        AdminSettingsJpaEntity(
            id = AdminSettingsJpaEntity.GLOBAL_ID,
            maxAllowedActiveTodos = maxAllowedActiveTodos,
            allowUserRegistration = allowUserRegistration,
            defaultPriority = defaultPriority,
            defaultEstimatedMinutes = defaultEstimatedMinutes,
        )
}

