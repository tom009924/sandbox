package com.example.todo.domain.exception

import java.util.UUID

class ProjectMemberAlreadyExistsException(projectId: UUID, userId: UUID) :
    RuntimeException("User $userId is already a member of project $projectId")
