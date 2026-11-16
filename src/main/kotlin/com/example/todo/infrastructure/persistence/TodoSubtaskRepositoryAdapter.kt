package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoSubtask
import com.example.todo.domain.repository.TodoSubtaskRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoSubtaskRepositoryAdapter(
    private val springDataTodoSubtaskRepository: SpringDataTodoSubtaskRepository,
    private val spannerTemplate: SpannerTemplate,
) : TodoSubtaskRepository {
    override fun save(subtask: TodoSubtask): TodoSubtask {
        val isNew = subtask.id == null
        val id = subtask.id ?: UUID.randomUUID()
        val entity = subtask.copy(id = id).toEntity()
        if (isNew) {
            spannerTemplate.insert(entity)
        } else {
            spannerTemplate.update(entity)
        }
        return entity.toDomain()
    }

    override fun findAllByTodoIdOrderByPositionAsc(todoId: UUID): List<TodoSubtask> =
        springDataTodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc(todoId.toString())
            .map { it.toDomain() }

    override fun findById(id: UUID): TodoSubtask? =
        springDataTodoSubtaskRepository.findById(id.toString()).orElse(null)?.toDomain()

    override fun deleteById(id: UUID) {
        springDataTodoSubtaskRepository.deleteById(id.toString())
    }

    override fun deleteAllByTodoId(todoId: UUID) {
        val entities = springDataTodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc(todoId.toString())
        if (entities.isNotEmpty()) {
            springDataTodoSubtaskRepository.deleteAll(entities)
        }
    }
}

private fun TodoSubtaskSpannerEntity.toDomain() = TodoSubtask(
    id = UUID.fromString(id),
    todoId = UUID.fromString(todoId),
    title = title,
    completed = completed,
    position = position.toInt(),
)

private fun TodoSubtask.toEntity() = TodoSubtaskSpannerEntity(
    id = (id ?: UUID.randomUUID()).toString(),
    todoId = todoId.toString(),
    title = title,
    completed = completed,
    position = position.toLong(),
)
