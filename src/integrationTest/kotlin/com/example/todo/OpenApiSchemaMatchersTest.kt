package com.example.todo

import tools.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.media.Schema
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
              "userId": "550e8400-e29b-41d4-a716-446655440001",
              "title": "Buy milk",
              "completed": false,
              "description": null,
              "dueDate": "2025-04-18",
              "priority": "HIGH",
              "estimatedMinutes": 30,
              "tags": ["groceries"]
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
              "userId": "550e8400-e29b-41d4-a716-446655440001",
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
        assertTrue(
            validateOpenApiSchema(
                """{"title":"Buy milk","priority":"INVALID_PRIORITY"}""",
                "CreateTodoRequest",
                openApi,
            ).isNotEmpty(),
        )
    }

    @Test
    fun undefinedOrUnsupportedTypeReturnsErrors() {
        val node = ObjectMapper().readTree("""{"foo":"bar"}""")
        val undefinedSchema = Schema<Any>()
        val undefinedErrors = validateSchema(node, undefinedSchema)
        assertEquals(listOf("$: schema type is undefined"), undefinedErrors)

        val unsupportedSchema = Schema<Any>().apply { type = "unknown_type" }
        val unsupportedErrors = validateSchema(node, unsupportedSchema)
        assertEquals(listOf("$: unsupported schema type 'unknown_type'"), unsupportedErrors)
    }

    @Test
    fun arrayWithoutItemsOrTypeMismatchReturnsErrors() {
        val arrayNode = ObjectMapper().readTree("""[1, 2, 3]""")
        val arraySchemaWithoutItems = Schema<Any>().apply { type = "array" }
        val missingItemsErrors = validateSchema(arrayNode, arraySchemaWithoutItems)
        assertEquals(listOf("$: array schema missing items definition"), missingItemsErrors)

        val stringSchema = Schema<Any>().apply { type = "string" }
        val mismatchErrors = validateSchema(arrayNode, stringSchema)
        assertEquals(listOf("$: expected string, got array"), mismatchErrors)
    }

    @Test
    fun integerSchemaRejectsNonIntegralNumbers() {
        val floatNode = ObjectMapper().readTree("3.14")
        val intSchema = Schema<Any>().apply { type = "integer" }
        val errors = validateSchema(floatNode, intSchema)
        assertEquals(listOf("$: expected integer, got number"), errors)
    }
}
