package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.repository.ProjectMemberRepository
import com.google.cloud.spanner.Key
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectMemberRepositoryAdapter(
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
    private val spannerTemplate: SpannerTemplate,
) : ProjectMemberRepository {
    override fun save(member: ProjectMember): ProjectMember {
        val entity = member.toEntity()
        val key = Key.of(entity.projectId, entity.userId)
        if (springDataProjectMemberRepository.existsById(key)) {
            spannerTemplate.update(entity)
        } else {
            spannerTemplate.insert(entity)
        }
        return entity.toDomain()
    }

    override fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember? =
        springDataProjectMemberRepository
            .findByProjectIdAndUserId(projectId.toString(), userId.toString())
            ?.toDomain()

    override fun findAllByProjectId(projectId: UUID): List<ProjectMember> =
        springDataProjectMemberRepository.findAllByProjectId(projectId.toString()).map { it.toDomain() }

    override fun findAllByUserId(userId: UUID): List<ProjectMember> =
        springDataProjectMemberRepository.findAllByUserId(userId.toString()).map { it.toDomain() }

    override fun countByProjectIdAndRole(projectId: UUID, role: ProjectRole): Long =
        springDataProjectMemberRepository.countByProjectIdAndRole(projectId.toString(), role.name)

    override fun deleteByProjectIdAndUserId(projectId: UUID, userId: UUID) {
        springDataProjectMemberRepository.deleteById(Key.of(projectId.toString(), userId.toString()))
    }

    override fun deleteAllByProjectId(projectId: UUID) {
        val entities = springDataProjectMemberRepository.findAllByProjectId(projectId.toString())
        if (entities.isNotEmpty()) {
            springDataProjectMemberRepository.deleteAll(entities)
        }
    }
}

private fun ProjectMemberSpannerEntity.toDomain() = ProjectMember(
    projectId = UUID.fromString(projectId),
    userId = UUID.fromString(userId),
    role = ProjectRole.valueOf(role),
    joinedAt = joinedAt,
)

private fun ProjectMember.toEntity() = ProjectMemberSpannerEntity(
    projectId = projectId.toString(),
    userId = userId.toString(),
    role = role.name,
    joinedAt = joinedAt,
)
