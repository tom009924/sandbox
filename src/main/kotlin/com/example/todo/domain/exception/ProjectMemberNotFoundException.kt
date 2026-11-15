package com.example.todo.domain.exception

import java.util.UUID

class ProjectMemberNotFoundException(projectId: UUID, userId: UUID) :
    RuntimeException("User $userId is not a member of project $projectId")
