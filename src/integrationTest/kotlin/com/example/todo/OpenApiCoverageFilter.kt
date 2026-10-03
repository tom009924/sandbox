package com.example.todo

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.PathItem.HttpMethod
import java.util.concurrent.ConcurrentHashMap

/**
 * REST Assured filter that tracks which OpenAPI endpoints have been requested during tests.
 */
class OpenApiCoverageFilter(
    private val expectedEndpoints: Set<Pair<HttpMethod, String>>,
) : Filter {
    private val coveredEndpointsInternal: MutableSet<Pair<HttpMethod, String>> = ConcurrentHashMap.newKeySet()

    val coveredEndpoints: Set<Pair<HttpMethod, String>>
        get() = coveredEndpointsInternal

    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        val method = runCatching {
            HttpMethod.valueOf(requestSpec.method.uppercase())
        }.getOrNull()

        if (method != null) {
            val actualPath = requestSpec.derivedPath
            for (endpoint in expectedEndpoints) {
                if (endpoint.first == method && matchesPath(actualPath, endpoint.second)) {
                    coveredEndpointsInternal.add(endpoint)
                }
            }
        }

        return ctx.next(requestSpec, responseSpec)
    }

    private fun matchesPath(actualPath: String, templatePath: String): Boolean {
        val regexPattern = templatePath.replace(Regex("""\{[^/]+}"""), "[^/]+")
        return actualPath.matches(Regex("^$regexPattern$"))
    }
}
