package com.example.todo.infrastructure.persistence

import com.google.cloud.spanner.Key
import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import org.springframework.stereotype.Repository

@Repository
interface SpringDataProjectMemberRepository : SpannerRepository<ProjectMemberSpannerEntity, Key> {
    fun findByProjectIdAndUserId(projectId: String, userId: String): ProjectMemberSpannerEntity?

    fun findAllByProjectId(projectId: String): List<ProjectMemberSpannerEntity>

    fun findAllByUserId(userId: String): List<ProjectMemberSpannerEntity>

    fun countByProjectIdAndRole(projectId: String, role: String): Long

    fun deleteByProjectIdAndUserId(projectId: String, userId: String)
}
