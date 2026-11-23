package com.example.integration.support

import com.example.integration.openapi.OpenApiValidationFilter
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * Shared OpenAPI specification, response validation filter, and JSON mapper for container integration tests.
 */
object ContainerOpenApiSpec {
    const val SPEC_PATH = "docs/dist/openapi.admin.yaml"

    val objectMapper: ObjectMapper = ObjectMapper()

    val openAPI: OpenAPI by lazy {
        requireNotNull(
            OpenAPIV3Parser().read(
                SPEC_PATH,
                null,
                ParseOptions().apply {
                    isResolve = true
                    isResolveFully = true
                },
            ),
        ) {
            "Failed to parse OpenAPI spec from $SPEC_PATH"
        }
    }

    val validationFilter: OpenApiValidationFilter by lazy {
        OpenApiValidationFilter(openAPI)
    }

    fun requireSchema(name: String): Schema<*> =
        requireNotNull(openAPI.components?.schemas?.get(name)) {
            "Schema '$name' not found in $SPEC_PATH"
        }
}
