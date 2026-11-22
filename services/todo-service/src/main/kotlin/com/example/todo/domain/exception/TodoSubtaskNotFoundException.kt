package com.example.todo.domain.exception

import java.util.UUID

class TodoSubtaskNotFoundException(subtaskId: UUID) :
    RuntimeException("Subtask $subtaskId not found")
