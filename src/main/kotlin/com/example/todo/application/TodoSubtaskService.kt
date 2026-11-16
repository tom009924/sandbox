package com.example.todo.application

import com.example.todo.domain.exception.TodoSubtaskNotFoundException
import com.example.todo.domain.model.TodoSubtask
import com.example.todo.domain.repository.TodoSubtaskRepository
import java.util.UUID

data class PatchTodoSubtaskCommand(
    val title: String? = null,
    val completed: Boolean? = null,
    val position: Int? = null,
)

class TodoSubtaskService(
    private val todoAccessChecker: TodoAccessChecker,
    private val todoSubtaskRepository: TodoSubtaskRepository,
) {
    fun list(todoId: UUID, userId: UUID): List<TodoSubtask> {
        todoAccessChecker.requireAccess(todoId, userId)
        return todoSubtaskRepository.findAllByTodoIdOrderByPositionAsc(todoId)
    }

    fun add(todoId: UUID, userId: UUID, title: String): TodoSubtask {
        todoAccessChecker.requireAccess(todoId, userId)
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

    fun patch(
        todoId: UUID,
        subtaskId: UUID,
        userId: UUID,
        command: PatchTodoSubtaskCommand,
    ): TodoSubtask {
        todoAccessChecker.requireAccess(todoId, userId)
        val existing = findSubtask(todoId, subtaskId)
        return todoSubtaskRepository.save(
            existing.copy(
                title = command.title?.trim() ?: existing.title,
                completed = command.completed ?: existing.completed,
                position = command.position ?: existing.position,
            ),
        )
    }

    fun delete(todoId: UUID, subtaskId: UUID, userId: UUID) {
        todoAccessChecker.requireAccess(todoId, userId)
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
}
