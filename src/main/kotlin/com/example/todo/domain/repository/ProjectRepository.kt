package com.example.todo.domain.repository

import com.example.todo.domain.model.Project
import java.util.UUID

interface ProjectRepository {
    fun save(project: Project): Project

    fun findById(id: UUID): Project?

    fun findAllByIdIn(ids: Collection<UUID>): List<Project>

    fun deleteById(id: UUID)
}
