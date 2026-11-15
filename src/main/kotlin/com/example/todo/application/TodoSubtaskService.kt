package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.exception.TodoSubtaskNotFoundException
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.TodoSubtaskRepository
import java.util.UUID

class TodoSubtaskService(
    private val todoRepository: TodoRepository,
    private val todoSubtaskRepository: TodoSubtaskRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {
    fun list(todoId: UUID, userId: UUID): List<TodoSubtask> {
        requireTodoAccess(todoId, userId)
        return todoSubtaskRepository.findAllByTodoIdOrderByPositionAsc(todoId)
    }

    fun add(todoId: UUID, userId: UUID, title: String): TodoSubtask {
        requireTodoAccess(todoId, userId)
        val existing = todoSubtaskRepository.findAllByTodoIdOrderByPositionAsc(todoId)
        val nextPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        return todoSubtaskRepository.save(
            TodoSubtask(
                todoId = todoId,
                title = title.trim(),
                completed = false,
                position = nextPosition,
            ),
        )
    }

    @Suppress("LongParameterList")
    fun patch(
        todoId: UUID,
        subtaskId: UUID,
        userId: UUID,
        title: String? = null,
        completed: Boolean? = null,
        position: Int? = null,
    ): TodoSubtask {
        requireTodoAccess(todoId, userId)
        val existing = findSubtask(todoId, subtaskId)
        return todoSubtaskRepository.save(
            existing.copy(
                title = title?.trim() ?: existing.title,
                completed = completed ?: existing.completed,
                position = position ?: existing.position,
            ),
        )
    }

    fun delete(todoId: UUID, subtaskId: UUID, userId: UUID) {
        requireTodoAccess(todoId, userId)
        findSubtask(todoId, subtaskId)
        todoSubtaskRepository.deleteById(subtaskId)
    }

    private fun findSubtask(todoId: UUID, subtaskId: UUID): TodoSubtask {
        val subtask = todoSubtaskRepository.findById(subtaskId)
        if (subtask == null || subtask.todoId != todoId) {
            throw TodoSubtaskNotFoundException(subtaskId)
        }
        return subtask
    }

    private fun requireTodoAccess(todoId: UUID, userId: UUID) {
        val todo = todoRepository.findById(todoId) ?: throw TodoNotFoundException(todoId)
        val projectId = todo.projectId
        val hasAccess = if (projectId != null) {
            projectMemberRepository.findByProjectIdAndUserId(projectId, userId) != null
        } else {
            todo.userId == userId
        }
        if (!hasAccess) {
            throw TodoNotFoundException(todoId)
        }
    }
}
