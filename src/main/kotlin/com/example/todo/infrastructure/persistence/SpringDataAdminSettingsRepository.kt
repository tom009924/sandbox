package com.example.todo.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SpringDataAdminSettingsRepository : JpaRepository<AdminSettingsJpaEntity, String>

