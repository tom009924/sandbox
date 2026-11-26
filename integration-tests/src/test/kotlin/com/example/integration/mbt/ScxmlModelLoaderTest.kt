package com.example.integration.mbt

import com.example.integration.support.ContainerOpenApiSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScxmlModelLoaderTest {
    @Test
    fun loadsTodoLifecycleScxmlAndAlignsWithOpenApiOperations() {
        val model = ScxmlModelLoader.load("docs/models/todo-lifecycle.scxml")
        assertEquals("TodoLifecycleModel", model.name)
        assertEquals("NotCreated", model.initial)
        assertTrue(model.states.containsAll(setOf("NotCreated", "Active", "Completed", "Deleted")))

        val openApiOperationIds = ContainerOpenApiSpec.openAPI.paths.orEmpty().values
            .flatMap { it.readOperations() }
            .mapNotNull { it.operationId }
            .toSet()

        val unknownEvents = model.allEvents() - openApiOperationIds
        assertTrue(unknownEvents.isEmpty()) {
            "SCXML transitions reference unknown OpenAPI operationIds: $unknownEvents"
        }
    }
}
