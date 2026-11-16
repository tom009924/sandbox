package com.example.todo.application

import com.example.todo.domain.exception.TodoCommentForbiddenException
import com.example.todo.domain.exception.TodoCommentNotFoundException
import com.example.todo.domain.model.TodoComment
import com.example.todo.domain.repository.TodoCommentRepository
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
    private val todoAccessChecker: TodoAccessChecker,
    private val todoCommentRepository: TodoCommentRepository,
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(todoId: UUID, userId: UUID): List<TodoCommentInfo> {
        todoAccessChecker.requireAccess(todoId, userId)
        val comments = todoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc(todoId)
        val usernamesById = comments
            .map { it.userId }
            .distinct()
            .associateWith { id -> userRepository.findById(id)?.username ?: id.toString() }
        return comments.map { comment ->
            TodoCommentInfo(
                id = requireNotNull(comment.id),
                todoId = comment.todoId,
                userId = comment.userId,
                username = usernamesById.getValue(comment.userId),
                content = comment.content,
                createdAt = comment.createdAt,
            )
        }
    }

    fun add(todoId: UUID, userId: UUID, content: String): TodoCommentInfo {
        todoAccessChecker.requireAccess(todoId, userId)
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
        todoAccessChecker.requireAccess(todoId, userId)
        val comment = todoCommentRepository.findById(commentId)
        if (comment == null || comment.todoId != todoId) {
            throw TodoCommentNotFoundException(commentId)
        }
        if (comment.userId != userId) {
            throw TodoCommentForbiddenException("Only the comment author can delete this comment")
        }
        todoCommentRepository.deleteById(commentId)
    }
}
