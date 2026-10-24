package com.example.todo.application

import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AdminStatsServiceTest {
    private val userRepository = mock(UserRepository::class.java)
    private val todoRepository = mock(TodoRepository::class.java)
    private val adminStatsService = AdminStatsService(userRepository, todoRepository)

    @Test
    fun `getStats returns aggregate counts`() {
        `when`(userRepository.count()).thenReturn(5L)
        `when`(todoRepository.count()).thenReturn(10L)
        `when`(todoRepository.countByCompleted(true)).thenReturn(4L)

        val stats = adminStatsService.getStats()

        assertEquals(5L, stats.totalUsers)
        assertEquals(10L, stats.totalTodos)
        assertEquals(4L, stats.completedTodos)
        assertEquals(6L, stats.activeTodos)
    }
}

