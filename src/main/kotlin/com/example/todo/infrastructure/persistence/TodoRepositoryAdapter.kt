package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.TodoRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepositoryAdapter(
    private val springDataTodoRepository: SpringDataTodoRepository,
) : TodoRepository {
    override fun findAll(): List<Todo> =
        springDataTodoRepository.findAll().map { it.toDomain() }

    override fun findById(id: UUID): Todo? =
        springDataTodoRepository.findById(id).orElse(null)?.toDomain()

    override fun save(todo: Todo): Todo =
        springDataTodoRepository.save(todo.toEntity()).toDomain()

    override fun delete(todo: Todo) {
        springDataTodoRepository.deleteById(requireNotNull(todo.id))
    }

    private fun TodoJpaEntity.toDomain() = Todo(
        id = id,
        title = title,
        completed = completed,
        description = description,
        dueDate = dueDate,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
    )

    private fun Todo.toEntity() = TodoJpaEntity(
        id = id,
        title = title,
        completed = completed,
        description = description,
        dueDate = dueDate,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
    )
}
