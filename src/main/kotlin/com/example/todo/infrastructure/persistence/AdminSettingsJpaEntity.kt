package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.AdminSettings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "admin_settings")
class AdminSettingsJpaEntity(
    @Id
    var id: String = GLOBAL_ID,

    @Column(nullable = false)
    var maxAllowedActiveTodos: Int = AdminSettings.DEFAULT_MAX_ALLOWED_ACTIVE_TODOS,

    @Column(nullable = false)
    var allowUserRegistration: Boolean = true,
) {
    companion object {
        const val GLOBAL_ID = "GLOBAL"
    }
}

