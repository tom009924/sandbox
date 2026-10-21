package com.example.todo

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiSchemaMatchersTest {
    private val openApi = requireNotNull(
        OpenAPIV3Parser().read(
            "docs/openapi.yaml",
            null,
            ParseOptions().apply {
                isResolve = true
                isResolveFully = true
            },
        ),
    )

    @Test
    fun validTodoJsonMatchesTodoSchema() {
        val validJson = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "title": "Buy milk",
              "completed": false,
              "description": null,
              "dueDate": "2025-04-18",
              "priority": "HIGH",
              "estimatedMinutes": 30
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), validateOpenApiSchema(validJson, "Todo", openApi))
    }

    @Test
    fun invalidTodoJsonFailsTodoSchemaMatcher() {
        val missingRequiredField = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "title": "Buy milk"
            }
        """.trimIndent()
        assertTrue(validateOpenApiSchema(missingRequiredField, "Todo", openApi).isNotEmpty())

        val invalidUuidAndPriority = """
            {
              "id": "not-a-uuid",
              "title": "Buy milk",
              "completed": false,
              "description": null,
              "dueDate": null,
              "priority": "URGENT",
              "estimatedMinutes": 10
            }
        """.trimIndent()
        assertTrue(validateOpenApiSchema(invalidUuidAndPriority, "Todo", openApi).isNotEmpty())

        val unknownAdditionalProperty = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "title": "Buy milk",
              "completed": false,
              "description": null,
              "dueDate": null,
              "priority": "LOW",
              "estimatedMinutes": null,
              "extraField": "unexpected"
            }
        """.trimIndent()
        assertTrue(validateOpenApiSchema(unknownAdditionalProperty, "Todo", openApi).isNotEmpty())
    }

    @Test
    fun createTodoRequestSchemaValidatesConstraints() {
        assertEquals(
            emptyList<String>(),
            validateOpenApiSchema(
                """{"title":"Buy milk","priority":"MEDIUM","estimatedMinutes":0}""",
                "CreateTodoRequest",
                openApi,
            ),
        )
        assertTrue(validateOpenApiSchema("""{"title":"   "}""", "CreateTodoRequest", openApi).isNotEmpty())
        assertTrue(
            validateOpenApiSchema(
                """{"title":"Buy milk","estimatedMinutes":-1}""",
                "CreateTodoRequest",
                openApi,
            ).isNotEmpty(),
        )
    }
}
