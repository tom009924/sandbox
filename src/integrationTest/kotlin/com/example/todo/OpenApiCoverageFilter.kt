package com.example.todo

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.PathItem.HttpMethod
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an expected OpenAPI response definition (HTTP method, path pattern, and status code).
 */
data class OpenApiResponse(
    val method: HttpMethod,
    val path: String,
    val statusCode: Int,
) {
    override fun toString(): String = "$method $path -> $statusCode"
}

/**
 * REST Assured filter that tracks which OpenAPI responses (method, path, status code)
 * have been exercised during tests.
 */
class OpenApiCoverageFilter(
    private val expectedResponses: Set<OpenApiResponse>,
) : Filter {
    private val coveredResponsesInternal: MutableSet<OpenApiResponse> = ConcurrentHashMap.newKeySet()

    val coveredResponses: Set<OpenApiResponse>
        get() = coveredResponsesInternal

    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        val response = ctx.next(requestSpec, responseSpec)

        val method = runCatching {
            HttpMethod.valueOf(requestSpec.method.uppercase())
        }.getOrNull()

        if (method != null) {
            val actualPath = requestSpec.derivedPath
            val statusCode = response.statusCode
            for (expected in expectedResponses) {
                if (expected.method == method &&
                    expected.statusCode == statusCode &&
                    matchesPath(actualPath, expected.path)
                ) {
                    coveredResponsesInternal.add(expected)
                }
            }
        }

        return response
    }

    private fun matchesPath(actualPath: String, templatePath: String): Boolean {
        val regexPattern = templatePath.replace(Regex("""\{[^/]+}"""), "[^/]+")
        return actualPath.matches(Regex("^$regexPattern$"))
    }
}
