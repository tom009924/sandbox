package com.example.todo.domain.repository

import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import java.util.UUID

interface ProjectMemberRepository {
    fun save(member: ProjectMember): ProjectMember

    fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember?

    fun findAllByProjectId(projectId: UUID): List<ProjectMember>

    fun findAllByUserId(userId: UUID): List<ProjectMember>

    fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole): Long

    fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID)

    fun deleteAllByProjectId(projectId: UUID)
}
