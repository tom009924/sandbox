package com.example.todo.domain.exception

import java.util.UUID

class LastProjectOwnerException(projectId: UUID) :
    RuntimeException("Cannot remove the last owner of project $projectId")
