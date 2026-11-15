package com.example.todo.domain.exception

import java.util.UUID

class ProjectNotFoundException(id: UUID) : RuntimeException("Project $id not found")
