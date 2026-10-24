package com.example.todo.domain.repository

import com.example.todo.domain.model.AdminSettings

interface AdminSettingsRepository {
    fun find(): AdminSettings?
    fun save(settings: AdminSettings): AdminSettings
    fun deleteAll()
}

