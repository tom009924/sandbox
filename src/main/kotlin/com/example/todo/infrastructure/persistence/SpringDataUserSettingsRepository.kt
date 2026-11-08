package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import org.springframework.stereotype.Repository

@Repository
interface SpringDataUserSettingsRepository : SpannerRepository<UserSettingsSpannerEntity, String>
