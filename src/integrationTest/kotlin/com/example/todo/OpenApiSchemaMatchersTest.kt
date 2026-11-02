package com.example.todo

import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.ResponseBody
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Suppress("TooManyFunctions")
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

    @Test
    fun extractPathParamsExtractsCorrectValues() {
        val params = extractPathParams("/api/todos/550e8400-e29b-41d4-a716-446655440000", "/api/todos/{id}")
        assertEquals(mapOf("id" to "550e8400-e29b-41d4-a716-446655440000"), params)

        val nonMatching = extractPathParams("/api/other/path", "/api/todos/{id}")
        assertTrue(nonMatching.isEmpty())
    }

    @Test
    fun validateParameterValueValidatesTypesAndFormats() {
        val uuidSchema = Schema<Any>().apply { format = "uuid" }
        assertTrue(validateParameterValue("id", "not-a-uuid", uuidSchema, "Path").isNotEmpty())
        assertTrue(validateParameterValue("id", "550e8400-e29b-41d4-a716-446655440000", uuidSchema, "Path").isEmpty())

        val boolSchema = Schema<Any>().apply { type = "boolean" }
        assertTrue(validateParameterValue("completed", "not-a-bool", boolSchema, "Query").isNotEmpty())
        assertTrue(validateParameterValue("completed", "true", boolSchema, "Query").isEmpty())

        val enumSchema = Schema<Any>().apply { enum = listOf("LOW", "MEDIUM", "HIGH") }
        assertTrue(validateParameterValue("priority", "UNKNOWN", enumSchema, "Query").isNotEmpty())
        assertTrue(validateParameterValue("priority", "HIGH", enumSchema, "Query").isEmpty())
    }

    @Test
    fun validateOpenApiRequestAcceptsValidRequest() {
        val requestSpec = mockRequest(
            method = "POST",
            path = "/api/todos",
            headers = Headers(Header("Idempotency-Key", UUID.randomUUID().toString())),
            body = """{"title":"Buy milk"}""",
        )

        val errors = validateOpenApiRequest(requestSpec, openApi)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun validateOpenApiRequestDetectsMissingHeaderOrInvalidBody() {
        // Missing Idempotency-Key
        val requestSpec = mockRequest(
            method = "POST",
            path = "/api/todos",
            headers = Headers(),
            body = """{"title":"Buy milk"}""",
        )

        val missingHeaderErrors = validateOpenApiRequest(requestSpec, openApi)
        assertTrue(missingHeaderErrors.any { it.contains("Idempotency-Key") })

        // Invalid body (blank title)
        val validHeader = Headers(Header("Idempotency-Key", UUID.randomUUID().toString()))
        Mockito.`when`(requestSpec.headers).thenReturn(validHeader)
        Mockito.`when`(requestSpec.getBody<Any>()).thenReturn("""{"title":"   "}""")
        val invalidBodyErrors = validateOpenApiRequest(requestSpec, openApi)
        assertTrue(invalidBodyErrors.any { it.contains("title") })
    }

    @Test
    fun validateOpenApiRequestDetectsUndocumentedEndpoint() {
        val requestSpec = mockRequest(method = "DELETE", path = "/api/nonexistent/endpoint")

        val errors = validateOpenApiRequest(requestSpec, openApi)
        assertEquals(listOf("Undocumented request: DELETE /api/nonexistent/endpoint"), errors)
    }

    @Test
    fun validateOpenApiResponseDetectsUndocumentedStatusAndInvalidSchema() {
        val requestSpec = mockRequest(method = "GET", path = "/api/todos")

        val response = Mockito.mock(Response::class.java)
        Mockito.`when`(response.statusCode).thenReturn(TEAPOT_STATUS)

        val undocumentedErrors = validateOpenApiResponse(response, requestSpec, openApi)
        assertEquals(listOf("Undocumented response: GET /api/todos -> $TEAPOT_STATUS"), undocumentedErrors)

        Mockito.`when`(response.statusCode).thenReturn(OK_STATUS)
        Mockito.`when`(response.contentType).thenReturn("application/json")
        val responseBody = Mockito.mock(ResponseBody::class.java)
        Mockito.`when`(responseBody.asString()).thenReturn("""{"invalid":"object"}""")
        Mockito.`when`(response.body).thenReturn(responseBody)

        val invalidSchemaErrors = validateOpenApiResponse(response, requestSpec, openApi)
        assertTrue(invalidSchemaErrors.isNotEmpty())
    }

    private fun mockRequest(
        method: String,
        path: String,
        headers: Headers = Headers(),
        body: String? = null,
    ): FilterableRequestSpecification {
        val spec = Mockito.mock(FilterableRequestSpecification::class.java)
        Mockito.`when`(spec.method).thenReturn(method)
        Mockito.`when`(spec.derivedPath).thenReturn(path)
        Mockito.`when`(spec.headers).thenReturn(headers)
        Mockito.`when`(spec.contentType).thenReturn("application/json")
        Mockito.`when`(spec.getBody<Any>()).thenReturn(body)
        Mockito.`when`(spec.queryParams).thenReturn(emptyMap())
        Mockito.`when`(spec.pathParams).thenReturn(emptyMap())
        return spec
    }

    private companion object {
        const val TEAPOT_STATUS = 418
        const val OK_STATUS = 200
    }
}
