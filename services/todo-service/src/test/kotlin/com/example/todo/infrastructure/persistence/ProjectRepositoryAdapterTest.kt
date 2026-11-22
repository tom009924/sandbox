package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["identity.service.enabled=false"])
class ProjectRepositoryAdapterTest @Autowired constructor(
    private val adapter: ProjectRepositoryAdapter,
    private val springDataProjectRepository: SpringDataProjectRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataProjectRepository.deleteAll()
    }

    @Test
    fun `save, find, and delete project`() {
        val creatorId = UUID.randomUUID()
        val now = Instant.parse("2025-04-18T10:00:00Z")
        val saved = adapter.save(
            Project(
                name = "Alpha",
                description = "First project",
                createdBy = creatorId,
                createdAt = now,
            ),
        )
        val projectId = requireNotNull(saved.id)

        val found = adapter.findById(projectId)
        assertNotNull(found)
        assertEquals("Alpha", found?.name)
        assertEquals(listOf(saved), adapter.findAllByIdIn(listOf(projectId)))
        assertEquals(emptyList<Project>(), adapter.findAllByIdIn(emptyList()))

        val updated = adapter.save(saved.copy(name = "Beta"))
        assertEquals("Beta", updated.name)

        adapter.deleteById(projectId)
        assertNull(adapter.findById(projectId))
    }
}
