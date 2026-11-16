package com.example.todo.application

import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.Todo
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.TodoRepository
import java.util.UUID

/**
 * Verifies that a user is allowed to access a given Todo item (either as its owner
 * or as a member of the Todo's project).
 */
class TodoAccessChecker(
    private val todoRepository: TodoRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {
    fun requireAccess(todoId: UUID, userId: UUID): Todo {
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
        return todo
    }
}
