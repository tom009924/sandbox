package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoComment
import com.example.todo.domain.repository.TodoCommentRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoCommentRepositoryAdapter(
    private val springDataTodoCommentRepository: SpringDataTodoCommentRepository,
    private val spannerTemplate: SpannerTemplate,
) : TodoCommentRepository {
    override fun save(comment: TodoComment): TodoComment {
        val isNew = comment.id == null
        val id = comment.id ?: UUID.randomUUID()
        val entity = comment.copy(id = id).toEntity()
        if (isNew) {
            spannerTemplate.insert(entity)
        } else {
            spannerTemplate.update(entity)
        }
        return entity.toDomain()
    }

    override fun findAllByTodoIdOrderByCreatedAtAsc(todoId: UUID): List<TodoComment> =
        springDataTodoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc(todoId.toString())
            .map { it.toDomain() }

    override fun findById(id: UUID): TodoComment? =
        springDataTodoCommentRepository.findById(id.toString()).orElse(null)?.toDomain()

    override fun deleteById(id: UUID) {
        springDataTodoCommentRepository.deleteById(id.toString())
    }

    override fun deleteAllByTodoId(todoId: UUID) {
        val entities = springDataTodoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc(todoId.toString())
        if (entities.isNotEmpty()) {
            springDataTodoCommentRepository.deleteAll(entities)
        }
    }
}

private fun TodoCommentSpannerEntity.toDomain() = TodoComment(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    userId = UUID.fromString(userId),
    content = content,
    createdAt = createdAt,
)

private fun TodoComment.toEntity() = TodoCommentSpannerEntity(
    id = (id ?: UUID.randomUUID()).toString(),
    todoId = todoId.toString(),
    userId = userId.toString(),
    content = content,
    createdAt = createdAt,
)
