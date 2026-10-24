package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.AdminSettings
import com.example.todo.domain.model.TodoPriority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var defaultPriority: TodoPriority = TodoPriority.MEDIUM,

    @Column(nullable = true)
    var defaultEstimatedMinutes: Int? = AdminSettings.DEFAULT_ESTIMATED_MINUTES,
) {
    companion object {
        const val GLOBAL_ID = "GLOBAL"
    }
}

