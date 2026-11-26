package com.example.integration.mbt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DmnModelLoaderTest {

    @Test
    fun loadsTodoDecisionsDmnWithAllDecisionsAndRules() {
        val model = DmnModelLoader.load(Path.of("docs/models/todo-decisions.dmn"))
        assertEquals("todo-decisions", model.id)
        assertEquals(
            setOf(
                "activeTodoLimitDecision",
                "projectMemberRemovalDecision",
                "idempotencyDecision",
                "todoAssigneeDecision",
            ),
            model.decisions.keys,
        )
        val activeDecision = model.decisions.getValue("activeTodoLimitDecision")
        assertEquals("FIRST", activeDecision.hitPolicy)
        assertEquals(listOf("createTodo", "updateTodo", "patchTodo"), activeDecision.operations)
        assertTrue(activeDecision.rules.any { it.id == "active_create_limit_exceeded" })
    }
}
