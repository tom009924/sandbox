package com.example.todo.application

import com.example.todo.domain.exception.TodoCommentForbiddenException
import com.example.todo.domain.exception.TodoCommentNotFoundException
import com.example.todo.domain.exception.TodoNotFoundException
import com.example.todo.domain.model.TodoComment
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.TodoCommentRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class TodoCommentInfo(
    val id: UUID,
    val todoId: UUID,
    val userId: UUID,
    val username: String,
    val content: String,
    val createdAt: Instant,
)

class TodoCommentService(
    private val todoRepository: TodoRepository,
    private val todoCommentRepository: TodoCommentRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(todoId: UUID, userId: UUID): List<TodoCommentInfo> {
        requireTodoAccess(todoId, userId)
        return todoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc(todoId).map { comment ->
            val username = userRepository.findById(comment.userId)?.username ?: comment.userId.toString()
            TodoCommentInfo(
                id = requireNotNull(comment.id),
                todoId = comment.todoId,
                userId = comment.userId,
                username = username,
                content = comment.content,
                createdAt = comment.createdAt,
            )
        }
    }

    fun add(todoId: UUID, userId: UUID, content: String): TodoCommentInfo {
        requireTodoAccess(todoId, userId)
        val saved = todoCommentRepository.save(
            TodoComment(
                todoId = todoId,
                userId = userId,
                content = content.trim(),
                createdAt = clock.instant(),
            ),
        )
        val username = userRepository.findById(userId)?.username ?: userId.toString()
        return TodoCommentInfo(
            id = requireNotNull(saved.id),
            todoId = saved.todoId,
            userId = saved.userId,
            username = username,
            content = saved.content,
            createdAt = saved.createdAt,
        )
    }

    fun delete(todoId: UUID, commentId: UUID, userId: UUID) {
        requireTodoAccess(todoId, userId)
        val comment = todoCommentRepository.findById(commentId)
        if (comment == null || comment.todoId != todoId) {
            throw TodoCommentNotFoundException(commentId)
        }
        if (comment.userId != userId) {
            throw TodoCommentForbiddenException("Only the comment author can delete this comment")
        }
        todoCommentRepository.deleteById(commentId)
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
