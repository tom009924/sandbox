package com.example.todo.domain.repository

import com.example.todo.domain.model.UserSettings
import java.util.UUID

interface UserSettingsRepository {
    fun findByUserId(userId: UUID): UserSettings?

    fun save(userSettings: UserSettings): UserSettings
}

