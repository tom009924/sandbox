package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.repository.TodoRepository
import com.google.cloud.spring.data.spanner.core.SpannerTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepositoryAdapter(
    private val springDataTodoRepository: SpringDataTodoRepository,
    private val spannerTemplate: SpannerTemplate,
) : TodoRepository {
    override fun findAll(): List<Todo> =
        springDataTodoRepository.findAll().map { it.toDomain() }

    override fun findAllByUserId(
        userId: UUID,
        completed: Boolean?,
        priority: TodoPriority?,
        tag: String?,
    ): List<Todo> {
        val uid = userId.toString()
        val entities = when {
            tag != null && completed != null && priority != null ->
                springDataTodoRepository.findAllByUserIdAndCompletedAndPriorityAndTag(
                    uid,
                    completed,
                    priority.name,
                    tag,
                )
            tag != null && completed != null ->
                springDataTodoRepository.findAllByUserIdAndCompletedAndTag(uid, completed, tag)
            tag != null && priority != null ->
                springDataTodoRepository.findAllByUserIdAndPriorityAndTag(uid, priority.name, tag)
            tag != null ->
                springDataTodoRepository.findAllByUserIdAndTag(uid, tag)
            completed != null && priority != null ->
                springDataTodoRepository.findAllByUserIdAndCompletedAndPriority(uid, completed, priority.name)
            completed != null ->
                springDataTodoRepository.findAllByUserIdAndCompleted(uid, completed)
            priority != null ->
                springDataTodoRepository.findAllByUserIdAndPriority(uid, priority.name)
            else ->
                springDataTodoRepository.findAllByUserId(uid)
        }
        return entities.map { it.toDomain() }
    }

    override fun findById(id: UUID): Todo? =
        springDataTodoRepository.findById(id.toString()).orElse(null)?.toDomain()

    override fun findByIdAndUserId(id: UUID, userId: UUID): Todo? =
        springDataTodoRepository.findByIdAndUserId(id.toString(), userId.toString())?.toDomain()

    override fun countByUserIdAndCompletedFalse(userId: UUID): Long =
        springDataTodoRepository.countByUserIdAndCompletedFalse(userId.toString())

    override fun count(): Long = springDataTodoRepository.count()

    override fun countByCompleted(completed: Boolean): Long =
        springDataTodoRepository.countByCompleted(completed)

    override fun save(todo: Todo): Todo {
        val isNew = todo.id == null
        val id = todo.id ?: UUID.randomUUID()
        val entity = todo.copy(id = id).toEntity()
        if (isNew) {
            spannerTemplate.insert(entity)
        } else {
            spannerTemplate.update(entity)
        }
        return entity.toDomain()
    }

    override fun delete(todo: Todo) {
        springDataTodoRepository.deleteById(requireNotNull(todo.id).toString())
    }
}

private fun TodoSpannerEntity.toDomain() = Todo(
    id = UUID.fromString(id),
    userId = UUID.fromString(userId),
    title = title,
    completed = completed,
    description = description,
    dueDate = dueDate,
    priority = TodoPriority.valueOf(priority),
    estimatedMinutes = estimatedMinutes,
    tags = tags.orEmpty(),
)

private fun Todo.toEntity() = TodoSpannerEntity(
    id = (id ?: UUID.randomUUID()).toString(),
    userId = userId.toString(),
    title = title,
    completed = completed,
    description = description,
    dueDate = dueDate,
    priority = priority.name,
    estimatedMinutes = estimatedMinutes,
    tags = tags,
)
