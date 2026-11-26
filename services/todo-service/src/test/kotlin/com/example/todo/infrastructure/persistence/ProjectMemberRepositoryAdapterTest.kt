package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.ProjectMember
import com.example.todo.domain.model.ProjectRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@Tag("persistence")
@SpringBootTest(properties = ["identity.service.enabled=false"])
class ProjectMemberRepositoryAdapterTest @Autowired constructor(
    private val adapter: ProjectMemberRepositoryAdapter,
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        springDataProjectMemberRepository.deleteAll()
    }

    @Test
    fun saveQueryAndDeleteProjectMembers() {
        val projectId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = Instant.parse("2025-04-18T10:00:00Z")

        val saved = adapter.save(
            ProjectMember(
                projectId = projectId,
                userId = userId,
                role = ProjectRole.OWNER,
                joinedAt = now,
            ),
        )
        assertEquals(ProjectRole.OWNER, saved.role)
        assertNotNull(adapter.findByProjectIdAndUserId(projectId, userId))
        assertEquals(1L, adapter.countByProjectIdAndRole(projectId, ProjectRole.OWNER))
        assertEquals(1, adapter.findAllByProjectId(projectId).size)
        assertEquals(1, adapter.findAllByUserId(userId).size)

        val updated = adapter.save(saved.copy(role = ProjectRole.MEMBER))
        assertEquals(ProjectRole.MEMBER, updated.role)

        adapter.deleteByProjectIdAndUserId(projectId, userId)
        assertNull(adapter.findByProjectIdAndUserId(projectId, userId))

        adapter.save(saved)
        adapter.deleteAllByProjectId(projectId)
        adapter.deleteAllByProjectId(projectId)
        assertEquals(0, adapter.findAllByProjectId(projectId).size)
    }
}
