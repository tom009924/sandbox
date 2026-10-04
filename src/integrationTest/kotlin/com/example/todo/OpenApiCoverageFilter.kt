package com.example.todo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
 * Represents an expected OpenAPI request validation constraint (e.g. required field, pattern).
 */
data class OpenApiConstraint(
    val method: HttpMethod,
    val path: String,
    val property: String,
    val constraintType: OpenApiConstraintType,
    val pattern: String? = null,
    val enumValue: String? = null,
) {
    init {
        require(constraintType != OpenApiConstraintType.PATTERN || pattern != null) {
            "A pattern is required for PATTERN constraints"
        }
        require(constraintType != OpenApiConstraintType.ENUM || enumValue != null) {
            "An enum value is required for ENUM constraints"
        }
    }

    override fun toString(): String =
        "$method $path -> $property (${constraintType.name.lowercase()}${enumValue?.let { ": $it" } ?: ""})"
}

enum class OpenApiConstraintType {
    REQUIRED,
    PATTERN,
    ENUM,
}

/**
 * REST Assured filter that tracks which OpenAPI responses (method, path, status code)
 * and request validation constraints have been exercised during tests.
 */
class OpenApiCoverageFilter(
    private val expectedResponses: Set<OpenApiResponse>,
    private val expectedConstraints: Set<OpenApiConstraint> = emptySet(),
) : Filter {
    private val coveredResponsesInternal: MutableSet<OpenApiResponse> = ConcurrentHashMap.newKeySet()
    private val coveredConstraintsInternal: MutableSet<OpenApiConstraint> = ConcurrentHashMap.newKeySet()

    val coveredResponses: Set<OpenApiResponse>
        get() = coveredResponsesInternal

    val coveredConstraints: Set<OpenApiConstraint>
        get() = coveredConstraintsInternal

    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        val requestBody = requestSpec.getBody<Any>()?.toString()
        val response = ctx.next(requestSpec, responseSpec)
        val method = HttpMethod.valueOf(requestSpec.method.uppercase())

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

        if (statusCode == BAD_REQUEST_STATUS && requestBody != null) {
            recordConstraints(method, actualPath, requestBody)
        }
        if (statusCode in SUCCESS_STATUS_RANGE && requestBody != null) {
            recordEnumValues(method, actualPath, requestBody)
        }

        return response
    }

    private fun recordConstraints(method: HttpMethod, actualPath: String, requestBody: String) {
        val rootNode = objectMapper.readTree(requestBody)
        expectedConstraints.asSequence()
            .filter { it.method == method && matchesPath(actualPath, it.path) }
            .filter { isViolated(it, rootNode) }
            .forEach(coveredConstraintsInternal::add)
    }

    private fun isViolated(constraint: OpenApiConstraint, rootNode: JsonNode): Boolean =
        when (constraint.constraintType) {
            OpenApiConstraintType.REQUIRED ->
                !rootNode.has(constraint.property) || rootNode.get(constraint.property).isNull
            OpenApiConstraintType.PATTERN -> {
                val node = rootNode.get(constraint.property)
                node != null && node.isTextual &&
                    !Regex(requireNotNull(constraint.pattern)).containsMatchIn(node.asText())
            }
            OpenApiConstraintType.ENUM -> false
        }

    private fun recordEnumValues(method: HttpMethod, actualPath: String, requestBody: String) {
        val rootNode = objectMapper.readTree(requestBody)
        expectedConstraints.asSequence()
            .filter {
                it.constraintType == OpenApiConstraintType.ENUM &&
                    it.method == method &&
                    matchesPath(actualPath, it.path)
            }
            .filter { rootNode.path(it.property).asText() == it.enumValue }
            .forEach(coveredConstraintsInternal::add)
    }

    private fun matchesPath(actualPath: String, templatePath: String): Boolean {
        val regexPattern = templatePath.replace(Regex("""\{[^/]+}"""), "[^/]+")
        return actualPath.matches(Regex("^$regexPattern$"))
    }

    private companion object {
        const val BAD_REQUEST_STATUS = 400
        val SUCCESS_STATUS_RANGE = 200..299
        val objectMapper = ObjectMapper()
    }
}
