package com.example.todo

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.Schema
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

data class OpenApiResponse(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val statusCode: Int,
) {
    override fun toString(): String = "$operationId ($method $path -> $statusCode)"
}

data class OpenApiProperty(
    val method: HttpMethod,
    val path: String,
    val name: String,
)

data class OpenApiParameter(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val name: String,
    val location: String,
    val isRequired: Boolean,
) {
    override fun toString(): String = "$operationId ($method $path -> $location:$name)"
}

sealed class OpenApiConstraint(val property: OpenApiProperty) {
    data class Required(val target: OpenApiProperty) : OpenApiConstraint(target)

    data class Pattern(val target: OpenApiProperty, val pattern: String) : OpenApiConstraint(target)

    data class EnumValue(val target: OpenApiProperty, val value: String) : OpenApiConstraint(target)

    data class MinimumBoundary(val target: OpenApiProperty, val minimum: BigDecimal) : OpenApiConstraint(target)

    data class BelowMinimum(val target: OpenApiProperty, val minimum: BigDecimal) : OpenApiConstraint(target)

    override fun toString(): String = when (this) {
        is Required -> "${property.name} (required)"
        is Pattern -> "${property.name} (pattern: $pattern)"
        is EnumValue -> "${property.name} (enum: $value)"
        is MinimumBoundary -> "${property.name} (minimum: $minimum)"
        is BelowMinimum -> "${property.name} (below minimum: $minimum)"
    }.let { "${property.method} ${property.path} -> $it" }
}

data class OpenApiCoverageDefinition(
    val responses: Set<OpenApiResponse>,
    val constraints: Set<OpenApiConstraint>,
    val parameters: Set<OpenApiParameter> = emptySet(),
) {
    val operationResponses: Set<Pair<String, Int>>
        get() = responses.map { it.operationId to it.statusCode }.toSet()

    companion object {
        fun from(openApi: OpenAPI): OpenApiCoverageDefinition {
            val schemas = openApi.components?.schemas.orEmpty()
            val operations = openApi.paths.orEmpty().flatMap { (path, pathItem) ->
                val pathParameters = pathItem.parameters.orEmpty()
                pathItem.readOperationsMap().map { (method, operation) ->
                    val operationId = operation.operationId ?: "${method.name.lowercase()}${path.replace("/", "_")}"
                    val allParameters = (pathParameters + operation.parameters.orEmpty())
                        .distinctBy { (it.name ?: "") to (it.`in` ?: "") }
                    OperationInfo(path, method, operation, operationId, allParameters)
                }
            }
            val responses = operations.flatMap { info ->
                info.operation.responses.keys.mapNotNull { it.toIntOrNull() }
                    .map { statusCode -> OpenApiResponse(info.operationId, info.method, info.path, statusCode) }
            }.toSet()
            val constraints = operations.flatMap { info ->
                val requestSchema = info.operation.requestBody?.content?.get("application/json")?.schema
                val schema = requestSchema?.`$ref`?.let { schemas[it.substringAfterLast("/")] } ?: requestSchema
                schema?.toConstraints(info.method, info.path, schemas).orEmpty()
            }.toSet()
            val parameters = operations.flatMap { info ->
                info.parameters.map { p ->
                    OpenApiParameter(
                        operationId = info.operationId,
                        method = info.method,
                        path = info.path,
                        name = p.name ?: "",
                        location = p.`in` ?: "",
                        isRequired = p.required == true,
                    )
                }
            }.toSet()
            return OpenApiCoverageDefinition(responses, constraints, parameters)
        }
    }
}

private data class OperationInfo(
    val path: String,
    val method: HttpMethod,
    val operation: io.swagger.v3.oas.models.Operation,
    val operationId: String,
    val parameters: List<io.swagger.v3.oas.models.parameters.Parameter>,
)

private fun Schema<*>.toConstraints(
    method: HttpMethod,
    path: String,
    schemas: Map<String, Schema<*>>,
): List<OpenApiConstraint> {
    val required = required.orEmpty().map { propertyName ->
        OpenApiConstraint.Required(OpenApiProperty(method, path, propertyName))
    }
    val properties = properties.orEmpty().flatMap { (name, schemaValue) ->
        val property = OpenApiProperty(method, path, name)
        buildList {
            schemaValue.pattern?.let { add(OpenApiConstraint.Pattern(property, it)) }
            schemaValue.enumValues(schemas).forEach { add(OpenApiConstraint.EnumValue(property, it)) }
            schemaValue.minimum?.let { minimum ->
                add(OpenApiConstraint.MinimumBoundary(property, minimum))
                add(OpenApiConstraint.BelowMinimum(property, minimum))
            }
        }
    }
    return required + properties
}

private fun Schema<*>.enumValues(schemas: Map<String, Schema<*>>): Set<String> {
    val targetSchema = `$ref`?.let { schemas[it.substringAfterLast("/")] } ?: this
    val directValues = (enum.orEmpty() + targetSchema.enum.orEmpty()).map { it.toString() }
    val referencedValues = (allOf.orEmpty() + targetSchema.allOf.orEmpty()).flatMap { component ->
        val schema = component.`$ref`?.let { schemas[it.substringAfterLast("/")] } ?: component
        schema.enum.orEmpty().map { it.toString() }
    }
    return (directValues + referencedValues).toSet()
}

class OpenApiCoverageFilter(
    private val definition: OpenApiCoverageDefinition,
) : Filter {
    private val coveredResponsesInternal: MutableSet<OpenApiResponse> = ConcurrentHashMap.newKeySet()
    private val coveredConstraintsInternal: MutableSet<OpenApiConstraint> = ConcurrentHashMap.newKeySet()
    private val coveredParametersInternal: MutableSet<OpenApiParameter> = ConcurrentHashMap.newKeySet()

    val coveredResponses: Set<OpenApiResponse>
        get() = coveredResponsesInternal

    val coveredConstraints: Set<OpenApiConstraint>
        get() = coveredConstraintsInternal

    val coveredParameters: Set<OpenApiParameter>
        get() = coveredParametersInternal

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

        recordResponse(method, actualPath, statusCode)
        recordParameters(requestSpec, method, actualPath)
        if (requestBody != null) {
            val body = objectMapper.readTree(requestBody)
            recordViolatedConstraints(method, actualPath, statusCode, body)
            recordSatisfiedConstraints(method, actualPath, statusCode, body)
        }
        return response
    }

    private fun recordParameters(
        requestSpec: FilterableRequestSpecification,
        method: HttpMethod,
        actualPath: String,
    ) {
        definition.parameters.asSequence()
            .filter { it.method == method && matchesPath(actualPath, it.path) }
            .filter { param ->
                when (param.location.lowercase()) {
                    "query" -> requestSpec.queryParams.containsKey(param.name)
                    "header" -> requestSpec.headers.hasHeaderWithName(param.name)
                    "path" -> true
                    else -> false
                }
            }
            .forEach(coveredParametersInternal::add)
    }

    private fun recordResponse(method: HttpMethod, actualPath: String, statusCode: Int) {
        definition.responses.asSequence()
            .filter { it.method == method && it.statusCode == statusCode }
            .filter { matchesPath(actualPath, it.path) }
            .forEach(coveredResponsesInternal::add)
    }

    private fun recordViolatedConstraints(
        method: HttpMethod,
        actualPath: String,
        statusCode: Int,
        body: JsonNode,
    ) {
        if (statusCode != BAD_REQUEST_STATUS) return
        matchingConstraints(method, actualPath)
            .filter { it.isViolatedBy(body) }
            .forEach(coveredConstraintsInternal::add)
    }

    private fun recordSatisfiedConstraints(
        method: HttpMethod,
        actualPath: String,
        statusCode: Int,
        body: JsonNode,
    ) {
        if (statusCode !in SUCCESS_STATUS_RANGE) return
        matchingConstraints(method, actualPath)
            .filter { it.isSatisfiedBy(body) }
            .forEach(coveredConstraintsInternal::add)
    }

    private fun matchingConstraints(method: HttpMethod, actualPath: String) =
        definition.constraints.asSequence().filter {
            it.property.method == method && matchesPath(actualPath, it.property.path)
        }

    private fun OpenApiConstraint.isViolatedBy(body: JsonNode): Boolean =
        when (this) {
            is OpenApiConstraint.Required ->
                !body.has(property.name) || body.get(property.name).isNull
            is OpenApiConstraint.Pattern -> {
                val node = body.get(property.name)
                node != null && node.isTextual && !Regex(pattern).containsMatchIn(node.asString())
            }
            is OpenApiConstraint.EnumValue,
            is OpenApiConstraint.MinimumBoundary -> false
            is OpenApiConstraint.BelowMinimum -> {
                val node = body.get(property.name)
                node != null && node.isNumber && node.decimalValue().compareTo(minimum) < 0
            }
        }

    private fun OpenApiConstraint.isSatisfiedBy(body: JsonNode): Boolean =
        when (this) {
            is OpenApiConstraint.EnumValue -> body.get(property.name)?.asString() == value
            is OpenApiConstraint.MinimumBoundary -> {
                val node = body.get(property.name)
                node != null && node.isNumber && node.decimalValue().compareTo(minimum) == 0
            }
            is OpenApiConstraint.Required,
            is OpenApiConstraint.Pattern,
            is OpenApiConstraint.BelowMinimum -> false
        }

    private companion object {
        const val BAD_REQUEST_STATUS = 400
        val SUCCESS_STATUS_RANGE = 200..299
        val objectMapper = ObjectMapper()
    }
}

internal fun matchesPath(actualPath: String, templatePath: String): Boolean {
    val regexPattern = templatePath.replace(Regex("""\{[^/]+}"""), "[^/]+")
    return actualPath.matches(Regex("^$regexPattern$"))
}
