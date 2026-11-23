package com.example.todo.mbt

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuintItfTraceLoaderTest {
    @Test
    fun loadsItfTraceAndVerifiesOpenApiAlignmentAndModelInvariants() {
        val trace = QuintItfTraceLoader.load("docs/models/traces/todo-model-trace.itf.json")
        assertEquals("docs/models/todo.qnt", trace.source)
        assertTrue(trace.steps.size > 1)
        assertEquals("init", trace.steps.first().lastAction)

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

        trace.steps.drop(1).forEach { step ->
            assertTrue(step.lastAction in openApiOperationIds) {
                "Step ${step.index} action '${step.lastAction}' is not in OpenAPI operationIds"
            }
            assertTrue(step.activeTodoCount() <= step.maxActiveTodos) {
                "Invariant violated at step ${step.index}: active=${step.activeTodoCount()} > ${step.maxActiveTodos}"
            }
        }
    }
}
