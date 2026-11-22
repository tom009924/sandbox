package com.example.todo.domain.exception

import java.util.UUID

class TodoNotFoundException(id: UUID) : RuntimeException("Todo $id not found")
