package com.example.todo

import io.kotest.assertions.json.schema.shouldMatchSchema
import io.kotest.assertions.json.schema.shouldNotMatchSchema
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
    fun createTodoRequestSchemaValidatesWithKotestShouldMatchSchema() {
        val createSchema = requireNotNull(openApi.components.schemas["CreateTodoRequest"])
            .toKotestJsonSchema(openApi)

        """{"title":"Buy milk","priority":"MEDIUM","estimatedMinutes":0}""" shouldMatchSchema createSchema
        """{"title":"   "}""" shouldNotMatchSchema createSchema
        """{"title":"Buy milk","estimatedMinutes":-1}""" shouldNotMatchSchema createSchema
    }
}
