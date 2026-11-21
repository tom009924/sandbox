package com.example.identity.domain.repository

import com.example.identity.domain.model.UserSettings
import java.util.UUID

interface UserSettingsRepository {
    fun findByUserId(userId: UUID): UserSettings?

    fun save(userSettings: UserSettings): UserSettings
}
