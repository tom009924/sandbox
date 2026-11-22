package com.example.todo.mbt

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
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

        val openApi = requireNotNull(
            OpenAPIV3Parser().read(
                "docs/dist/openapi.admin.yaml",
                null,
                ParseOptions().apply { isResolve = true },
            ),
        )
        val openApiOperationIds = openApi.paths.orEmpty().values
            .flatMap { it.readOperations() }
            .mapNotNull { it.operationId }
            .toSet()

        val unknownEvents = model.allEvents() - openApiOperationIds
        assertTrue(unknownEvents.isEmpty()) {
            "SCXML transitions reference unknown OpenAPI operationIds: $unknownEvents"
        }
    }
}
