package com.example.todo

import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Test

class OpenApiSchemaMatchersTest {
    private val openApi = requireNotNull(OpenAPIV3Parser().read("docs/openapi.yaml"))

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

        validJson should matchOpenApiSchema("Todo", openApi)
    }

    @Test
    fun invalidTodoJsonFailsTodoSchemaMatcher() {
        val missingRequiredField = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "title": "Buy milk"
            }
        """.trimIndent()
        missingRequiredField shouldNot matchOpenApiSchema("Todo", openApi)

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
        invalidUuidAndPriority shouldNot matchOpenApiSchema("Todo", openApi)

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
        unknownAdditionalProperty shouldNot matchOpenApiSchema("Todo", openApi)
    }

    @Test
    fun createTodoRequestSchemaValidatesConstraints() {
        """{"title":"Buy milk","priority":"MEDIUM","estimatedMinutes":0}""" should
            matchOpenApiSchema("CreateTodoRequest", openApi)
        """{"title":"   "}""" shouldNot matchOpenApiSchema("CreateTodoRequest", openApi)
        """{"title":"Buy milk","estimatedMinutes":-1}""" shouldNot matchOpenApiSchema("CreateTodoRequest", openApi)
    }
}
