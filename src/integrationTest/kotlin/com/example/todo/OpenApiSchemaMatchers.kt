@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.example.todo

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class OpenApiValidationFilter(
    private val openApi: OpenAPI,
    private val validateRequest: Boolean = true,
    private val validateResponse: Boolean = true,
) : Filter {
    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        if (validateRequest) {
            val requestErrors = validateOpenApiRequest(requestSpec, openApi)
            val msg = "OpenAPI request validation failed for ${requestSpec.method} " +
                "${requestSpec.derivedPath}: $requestErrors"
            assertTrue(requestErrors.isEmpty(), msg)
        }

        val response = ctx.next(requestSpec, responseSpec)

        if (validateResponse) {
            val responseErrors = validateOpenApiResponse(response, requestSpec, openApi)
            val msg = "OpenAPI response validation failed for ${requestSpec.method} " +
                "${requestSpec.derivedPath} -> ${response.statusCode}: $responseErrors"
            assertTrue(responseErrors.isEmpty(), msg)
        }

        return response
    }
}

fun findOpenApiOperation(
    openApi: OpenAPI,
    method: HttpMethod,
    path: String,
): Pair<String, Operation>? =
    openApi.paths.orEmpty().entries
        .firstOrNull { (tpl, _) -> matchesPath(path, tpl) }
        ?.let { (tpl, pathItem) ->
            pathItem.readOperationsMap()[method]?.let { op -> tpl to op }
        }

fun validateOpenApiRequest(
    requestSpec: FilterableRequestSpecification,
    openApi: OpenAPI,
): List<String> {
    val method = runCatching { HttpMethod.valueOf(requestSpec.method.uppercase()) }.getOrNull()
    val path = requestSpec.derivedPath
    val match = method?.let { findOpenApiOperation(openApi, it, path) }
    return when {
        method == null -> listOf("Unsupported HTTP method: ${requestSpec.method}")
        match == null -> listOf("Undocumented request: $method $path")
        else -> {
            val (templatePath, operation) = match
            validateParameters(requestSpec, templatePath, operation.parameters.orEmpty()) +
                validateRequestBody(requestSpec, operation, openApi)
        }
    }
}

fun validateOpenApiResponse(
    response: Response,
    requestSpec: FilterableRequestSpecification,
    openApi: OpenAPI,
): List<String> {
    val method = runCatching { HttpMethod.valueOf(requestSpec.method.uppercase()) }.getOrNull()
    val path = requestSpec.derivedPath
    val match = method?.let { findOpenApiOperation(openApi, it, path) }
    val apiResponse = match?.second?.responses?.get(response.statusCode.toString())
    return when {
        method == null -> listOf("Unsupported HTTP method: ${requestSpec.method}")
        match == null -> listOf("Undocumented request: $method $path")
        apiResponse == null -> listOf("Undocumented response: $method $path -> ${response.statusCode}")
        else -> validateResponseBody(response, apiResponse, openApi)
    }
}

private fun validateResponseBody(
    response: Response,
    apiResponse: ApiResponse,
    openApi: OpenAPI,
): List<String> {
    val contentType = response.contentType.substringBefore(";").trim()
    val schema = apiResponse.content?.let { it[contentType] ?: it["application/json"] }?.schema
    return if (schema != null) {
        validateOpenApiSchema(response.body.asString(), resolveSchema(schema, openApi))
    } else {
        emptyList()
    }
}

private fun validateParameters(
    requestSpec: FilterableRequestSpecification,
    templatePath: String,
    parameters: List<Parameter>,
): List<String> {
    val pathParams = extractPathParams(requestSpec.derivedPath, templatePath)
    return parameters.flatMap { param ->
        when (param.`in`) {
            "header" -> validateHeaderParam(requestSpec, param)
            "query" -> validateQueryParam(requestSpec, param)
            "path" -> validatePathParam(requestSpec, pathParams, param)
            else -> emptyList()
        }
    }
}

private fun validateHeaderParam(requestSpec: FilterableRequestSpecification, param: Parameter): List<String> {
    val value = requestSpec.headers.getValue(param.name)
    return when {
        param.required == true && value == null -> listOf("Missing required header '${param.name}'")
        value != null && param.schema != null -> validateParameterValue(param.name, value, param.schema, "Header")
        else -> emptyList()
    }
}

private fun validateQueryParam(requestSpec: FilterableRequestSpecification, param: Parameter): List<String> {
    val value = requestSpec.queryParams[param.name]
    return when {
        param.required == true && value == null -> listOf("Missing required query parameter '${param.name}'")
        value != null && param.schema != null -> validateParameterValue(param.name, value, param.schema, "Query")
        else -> emptyList()
    }
}

private fun validatePathParam(
    requestSpec: FilterableRequestSpecification,
    pathParams: Map<String, String>,
    param: Parameter,
): List<String> {
    val value = pathParams[param.name] ?: requestSpec.pathParams[param.name]
    return when {
        param.required == true && value == null -> listOf("Missing required path parameter '${param.name}'")
        value != null && param.schema != null -> validateParameterValue(param.name, value, param.schema, "Path")
        else -> emptyList()
    }
}

private fun validateRequestBody(
    requestSpec: FilterableRequestSpecification,
    operation: Operation,
    openApi: OpenAPI,
): List<String> {
    val requestBodyDef = operation.requestBody ?: return emptyList()
    val body = requestSpec.getBody<Any>()?.toString()
    val hasBody = !body.isNullOrBlank()

    return when {
        requestBodyDef.required == true && !hasBody -> listOf("Missing required request body")
        hasBody -> {
            val contentType = requestSpec.contentType?.substringBefore(";")?.trim() ?: "application/json"
            val mediaType = requestBodyDef.content?.get(contentType)
                ?: requestBodyDef.content?.get("application/json")
            val schema = mediaType?.schema
            if (schema != null) {
                validateOpenApiSchema(requireNotNull(body), resolveSchema(schema, openApi))
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }
}

fun extractPathParams(actualPath: String, templatePath: String): Map<String, String> {
    val paramNames = Regex("""\{([^/]+)}""").findAll(templatePath).map { it.groupValues[1] }.toList()
    val regexPattern = "^" + templatePath.replace(Regex("""\{[^/]+}"""), "([^/]+)") + "$"
    val match = Regex(regexPattern).matchEntire(actualPath)
    return if (paramNames.isNotEmpty() && match != null) {
        paramNames.zip(match.groupValues.drop(1)).toMap()
    } else {
        emptyMap()
    }
}

fun validateParameterValue(name: String, value: String, schema: Schema<*>, location: String): List<String> =
    buildList {
        schema.pattern?.let {
            if (!Regex(it).containsMatchIn(value)) {
                add("$location parameter '$name' does not match pattern '$it'")
            }
        }
        val enums = schema.enum.orEmpty().map { it.toString() }
        if (enums.isNotEmpty() && value !in enums) {
            add("$location parameter '$name' value '$value' is not in $enums")
        }
        when (schema.type) {
            "boolean" -> if (value != "true" && value != "false") {
                add("$location parameter '$name' expected boolean, got '$value'")
            }
            "integer" -> if (value.toLongOrNull() == null) {
                add("$location parameter '$name' expected integer, got '$value'")
            }
        }
        when (schema.format) {
            "uuid" -> runCatching { UUID.fromString(value) }.onFailure {
                add("$location parameter '$name' is not a valid UUID: '$value'")
            }
            "date" -> runCatching { LocalDate.parse(value) }.onFailure {
                add("$location parameter '$name' is not a valid date: '$value'")
            }
        }
    }

fun resolveSchema(schema: Schema<*>, openApi: OpenAPI): Schema<*> {
    val ref = schema.`$ref` ?: return schema
    val schemaName = ref.substringAfterLast("/")
    return openApi.components?.schemas?.get(schemaName) ?: schema
}

fun validateOpenApiSchema(json: String, schemaName: String, openApi: OpenAPI): List<String> =
    validateOpenApiSchema(json, requireNotNull(openApi.components?.schemas?.get(schemaName)))

fun validateOpenApiSchema(json: String, schema: Schema<*>, path: String = "$"): List<String> =
    validateSchema(objectMapper.readTree(json), schema, path)

fun validateSchema(node: JsonNode, schema: Schema<*>, path: String = "$"): List<String> =
    when {
        node.isNull -> if (schema.nullable == true) emptyList() else listOf("$path: null is not allowed")
        !schema.allOf.isNullOrEmpty() -> schema.allOf.flatMap { validateSchema(node, it, path) }
        else -> validateByType(node, schema, path)
    }

private fun validateByType(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    when (val type = schema.type) {
        "object" -> validateObject(node, schema, path)
        "array" -> validateArray(node, schema, path)
        "string" -> validateString(node, schema, path)
        "integer" -> validateNumber(node, schema, path, integerOnly = true)
        "number" -> validateNumber(node, schema, path, integerOnly = false)
        "boolean" -> validateBoolean(node, path)
        null -> if (schema.properties != null) {
            validateObject(node, schema, path)
        } else {
            listOf("$path: schema type is undefined")
        }
        else -> listOf("$path: unsupported schema type '$type'")
    }

private fun validateBoolean(node: JsonNode, path: String): List<String> =
    if (node.isBoolean) {
        emptyList()
    } else {
        listOf("$path: expected boolean, got ${node.nodeTypeName}")
    }

private fun validateObject(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isObject) {
        listOf("$path: expected object, got ${node.nodeTypeName}")
    } else {
        val props = schema.properties.orEmpty()
        val missing = schema.required.orEmpty().filter { !node.has(it) }.map { "$path.$it: required" }
        val extra = if (schema.additionalProperties != true && props.isNotEmpty()) {
            node.propertyNames().asSequence().filter { it !in props }.map { "$path.$it: unexpected property" }.toList()
        } else {
            emptyList<String>()
        }
        val children = props.flatMap { (k, v) ->
            if (node.has(k)) validateSchema(node.get(k), v, "$path.$k") else emptyList()
        }
        missing + extra + children
    }

private fun validateArray(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isArray) {
        listOf("$path: expected array, got ${node.nodeTypeName}")
    } else {
        val itemsSchema = schema.items
        if (itemsSchema == null) {
            listOf("$path: array schema missing items definition")
        } else {
            node.flatMapIndexed { i, item -> validateSchema(item, itemsSchema, "$path[$i]") }
        }
    }

private fun validateString(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isTextual) {
        listOf("$path: expected string, got ${node.nodeTypeName}")
    } else {
        val text = node.asString()
        val enums = schema.enum.orEmpty().map { it.toString() }
        buildList {
            schema.pattern?.let { if (!Regex(it).containsMatchIn(text)) add("$path: pattern '$it' mismatch") }
            if (enums.isNotEmpty() && text !in enums) add("$path: '$text' not in $enums")
            when (schema.format) {
                "uuid" -> runCatching { UUID.fromString(text) }.onFailure { add("$path: invalid uuid") }
                "date" -> runCatching { LocalDate.parse(text) }.onFailure { add("$path: invalid date") }
                "date-time" -> runCatching { OffsetDateTime.parse(text) }.onFailure { add("$path: invalid date-time") }
            }
        }
    }

private fun validateNumber(node: JsonNode, schema: Schema<*>, path: String, integerOnly: Boolean): List<String> =
    if (integerOnly && !node.isIntegralNumber) {
        listOf("$path: expected integer, got ${node.nodeTypeName}")
    } else if (!integerOnly && !node.isNumber) {
        listOf("$path: expected number, got ${node.nodeTypeName}")
    } else {
        val value = node.decimalValue()
        buildList {
            schema.minimum?.let { min -> if (value < min) add("$path: below minimum $min") }
            schema.maximum?.let { max -> if (value > max) add("$path: above maximum $max") }
        }
    }

private val JsonNode.nodeTypeName: String
    get() = nodeType.name.lowercase()

private val objectMapper = ObjectMapper()


