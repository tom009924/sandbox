package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Project
import com.example.todo.domain.repository.ProjectRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectRepositoryAdapter(
    private val springDataProjectRepository: SpringDataProjectRepository,
    private val spannerTemplate: SpannerTemplate,
) : ProjectRepository {
    override fun save(project: Project): Project {
        val isNew = project.id == null
        val id = project.id ?: UUID.randomUUID()
        val entity = project.copy(id = id).toEntity()
        if (isNew) {
            spannerTemplate.insert(entity)
        } else {
            spannerTemplate.update(entity)
        }
        return entity.toDomain()
    }

    override fun findById(id: UUID): Project? =
        springDataProjectRepository.findById(id.toString()).orElse(null)?.toDomain()

    override fun findAllByIdIn(ids: Collection<UUID>): List<Project> {
        if (ids.isEmpty()) return emptyList()
        return springDataProjectRepository.findAllByIdIn(ids.map { it.toString() }).map { it.toDomain() }
    }

    override fun deleteById(id: UUID) {
        springDataProjectRepository.deleteById(id.toString())
    }
}

private fun ProjectSpannerEntity.toDomain() = Project(
    id = UUID.fromString(id),
    name = name,
    description = description,
    createdBy = UUID.fromString(createdBy),
    createdAt = createdAt,
)

private fun Project.toEntity() = ProjectSpannerEntity(
    id = (id ?: UUID.randomUUID()).toString(),
    name = name,
    description = description,
    createdBy = createdBy.toString(),
    createdAt = createdAt,
)
