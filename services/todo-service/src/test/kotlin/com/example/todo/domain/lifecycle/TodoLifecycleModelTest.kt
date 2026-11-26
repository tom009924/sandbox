package com.example.todo.domain.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TodoLifecycleModelTest {
    @Test
    fun derivesInitialStateAndTransitionsFromScxmlModel() {
        assertEquals(TodoLifecycleState.NOT_CREATED, TodoLifecycleModel.initialState)
        assertTrue(
            TodoLifecycleModel.isTransitionAllowed(
                from = TodoLifecycleState.NOT_CREATED,
                event = TodoLifecycleEvent.CREATE_TODO,
                to = TodoLifecycleState.ACTIVE,
            ),
        )
        assertTrue(
            TodoLifecycleModel.isTransitionAllowed(
                from = TodoLifecycleState.ACTIVE,
                event = TodoLifecycleEvent.UPDATE_TODO,
                to = TodoLifecycleState.COMPLETED,
            ),
        )
        assertFalse(
            TodoLifecycleModel.isTransitionAllowed(
                from = TodoLifecycleState.DELETED,
                event = TodoLifecycleEvent.CREATE_TODO,
                to = TodoLifecycleState.ACTIVE,
            ),
        )
    }

    @Test
    fun derivesActiveLimitGuardRulesFromScxmlConditions() {
        assertTrue(
            TodoLifecycleModel.requiresActiveLimitCheck(
                from = TodoLifecycleState.NOT_CREATED,
                event = TodoLifecycleEvent.CREATE_TODO,
                to = TodoLifecycleState.ACTIVE,
            ),
        )
        assertTrue(
            TodoLifecycleModel.requiresActiveLimitCheck(
                from = TodoLifecycleState.COMPLETED,
                event = TodoLifecycleEvent.UPDATE_TODO,
                to = TodoLifecycleState.ACTIVE,
            ),
        )
        assertTrue(
            TodoLifecycleModel.requiresActiveLimitCheck(
                from = TodoLifecycleState.COMPLETED,
                event = TodoLifecycleEvent.PATCH_TODO,
                to = TodoLifecycleState.ACTIVE,
            ),
        )
        assertFalse(
            TodoLifecycleModel.requiresActiveLimitCheck(
                from = TodoLifecycleState.ACTIVE,
                event = TodoLifecycleEvent.UPDATE_TODO,
                to = TodoLifecycleState.COMPLETED,
            ),
        )
    }
}
