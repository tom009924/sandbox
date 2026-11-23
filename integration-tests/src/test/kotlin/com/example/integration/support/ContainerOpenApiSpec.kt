package com.example.integration.support

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * Shared OpenAPI specification loader for container integration tests.
 */
object ContainerOpenApiSpec {
    const val SPEC_PATH = "docs/dist/openapi.admin.yaml"

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
}
