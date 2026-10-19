package com.example.todo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.json.schema.JsonSchema
import io.kotest.assertions.json.schema.JsonSchemaElement
import io.kotest.assertions.json.schema.jsonSchema
import io.kotest.assertions.json.schema.shouldMatchSchema
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.and
import io.kotest.matchers.collections.beIn
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.beGreaterThanOrEqualTo as beDoubleGte
import io.kotest.matchers.doubles.beLessThanOrEqualTo as beDoubleLte
import io.kotest.matchers.longs.beGreaterThanOrEqualTo
import io.kotest.matchers.longs.beLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.string.contain
import io.kotest.matchers.string.haveMaxLength
import io.kotest.matchers.string.haveMinLength
import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import java.time.LocalDate
import java.util.UUID

fun Schema<*>.toKotestJsonSchema(openApi: OpenAPI): JsonSchema {
    val converter = KotestOpenApiSchemaConverter(openApi.components?.schemas.orEmpty())
    return jsonSchema { converter.toElement(this@toKotestJsonSchema) }
}

fun matchOpenApiSchema(
    schemaName: String,
    openApi: OpenAPI,
): Matcher<String> {
    val schema = requireNotNull(openApi.components?.schemas?.get(schemaName)) {
        "Schema '$schemaName' not found in OpenAPI components"
    }
    return matchOpenApiSchema(schema, openApi)
}

fun matchOpenApiSchema(
    schema: Schema<*>,
    openApi: OpenAPI,
): Matcher<String> = Matcher { actualJson ->
    val converter = KotestOpenApiSchemaConverter(openApi.components?.schemas.orEmpty())
    val kotestSchema = jsonSchema { converter.toElement(schema) }
    val parsedResult = runCatching {
        val node = schemaObjectMapper.readTree(actualJson)
        requireNotNull(node) { "JSON body is empty" }
    }

    parsedResult.fold(
        onSuccess = { rootNode ->
            val nullViolations = converter.validateNullabilityAndRequired(rootNode, schema, "$")
            if (nullViolations.isNotEmpty()) {
                MatcherResult(
                    passed = false,
                    failureMessageFn = {
                        "JSON did not match OpenAPI schema:\n" + nullViolations.joinToString("\n") { " - $it" }
                    },
                    negatedFailureMessageFn = { "Expected JSON not to match OpenAPI schema, but it matched." },
                )
            } else {
                val strippedNode = converter.stripNullableNulls(rootNode, schema)
                val strippedJson = schemaObjectMapper.writeValueAsString(strippedNode)
                val schemaCheck = runCatching { strippedJson shouldMatchSchema kotestSchema }
                MatcherResult(
                    passed = schemaCheck.isSuccess,
                    failureMessageFn = {
                        schemaCheck.exceptionOrNull()?.message ?: "JSON did not match OpenAPI schema"
                    },
                    negatedFailureMessageFn = { "Expected JSON not to match OpenAPI schema, but it matched." },
                )
            }
        },
        onFailure = { ex ->
            MatcherResult(
                passed = false,
                failureMessageFn = { "$: Invalid JSON (${ex.message})" },
                negatedFailureMessageFn = { "Expected valid JSON not matching schema" },
            )
        },
    )
}

class OpenApiValidationFilter(
    private val openApi: OpenAPI,
    private val validateRequest: Boolean = true,
) : Filter {
    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        val method = HttpMethod.valueOf(requestSpec.method.uppercase())
        val actualPath = requestSpec.derivedPath
        val (templatePath, pathItem, operation) = findOperation(method, actualPath)

        if (validateRequest) {
            validateRequestInteraction(requestSpec, templatePath, actualPath, pathItem, operation)
        }

        val response = ctx.next(requestSpec, responseSpec)
        validateResponseInteraction(response, method, actualPath, operation)
        return response
    }

    private fun findOperation(
        method: HttpMethod,
        actualPath: String,
    ): Triple<String, PathItem, Operation> {
        val matchedEntry = openApi.paths.orEmpty().entries.firstOrNull { (templatePath, _) ->
            matchesTemplatePath(actualPath, templatePath)
        }.shouldNotBeNull()
        val operation = matchedEntry.value.readOperationsMap()[method].shouldNotBeNull()
        return Triple(matchedEntry.key, matchedEntry.value, operation)
    }

    private fun validateRequestInteraction(
        requestSpec: FilterableRequestSpecification,
        templatePath: String,
        actualPath: String,
        pathItem: PathItem,
        operation: Operation,
    ) {
        val allParameters = pathItem.parameters.orEmpty() + operation.parameters.orEmpty()
        val paramErrors = validateParameters(requestSpec, templatePath, actualPath, allParameters)
        paramErrors.shouldBeEmpty()

        val requestBodySchema = operation.requestBody?.content?.get("application/json")?.schema
        if (requestBodySchema != null) {
            val rawBody = requestSpec.getBody<Any>()?.toString().orEmpty()
            rawBody should matchOpenApiSchema(requestBodySchema, openApi)
        }
    }

    private fun validateParameters(
        requestSpec: FilterableRequestSpecification,
        templatePath: String,
        actualPath: String,
        parameters: List<Parameter>,
    ): List<String> {
        val pathValues = extractPathParams(templatePath, actualPath)
        return parameters.flatMap { parameter ->
            val value = when (parameter.`in`) {
                "header" -> requestSpec.headers.getValue(parameter.name)
                "path" -> pathValues[parameter.name]
                else -> null
            }
            val paramSchema = parameter.schema
            when {
                value == null && parameter.required == true ->
                    listOf("Missing required ${parameter.`in`} parameter '${parameter.name}'")
                value != null && paramSchema != null -> {
                    val encoded = schemaObjectMapper.writeValueAsString(value)
                    val result = matchOpenApiSchema(paramSchema, openApi).test(encoded)
                    if (result.passed()) {
                        emptyList()
                    } else {
                        listOf("${parameter.`in`}.${parameter.name}: ${result.failureMessage()}")
                    }
                }
                else -> emptyList()
            }
        }
    }

    private fun validateResponseInteraction(
        response: Response,
        method: HttpMethod,
        actualPath: String,
        operation: Operation,
    ) {
        val statusKey = response.statusCode.toString()
        val apiResponse = requireNotNull(operation.responses[statusKey]) {
            "Unexpected response status $statusKey for $method $actualPath. " +
                "Documented statuses: ${operation.responses.keys}"
        }
        val contentMap = apiResponse.content.orEmpty()
        if (contentMap.isNotEmpty()) {
            val contentType = response.contentType.substringBefore(";").trim()
            val mediaType = contentMap[contentType] ?: contentMap["application/json"]
            val responseSchema = requireNotNull(mediaType?.schema) {
                "No schema defined for Content-Type '$contentType' on $method $actualPath ($statusKey)"
            }
            response.body.asString() should matchOpenApiSchema(responseSchema, openApi)
        }
    }
}

private class KotestOpenApiSchemaConverter(
    private val schemas: Map<String, Schema<*>>,
) {
    fun toElement(rawSchema: Schema<*>): JsonSchemaElement {
        val schema = resolveSchema(rawSchema)
        return when (schema.type) {
            "object" -> toObjectElement(schema)
            "array" -> JsonSchema.JsonArray(elementType = schema.items?.let(::toElement))
            "string" -> JsonSchema.JsonString(matcher = buildStringMatcher(schema))
            "integer" -> JsonSchema.JsonInteger(matcher = buildIntegerMatcher(schema))
            "number" -> JsonSchema.JsonDecimal(matcher = buildNumberMatcher(schema))
            "boolean" -> JsonSchema.JsonBoolean
            else -> JsonSchema.JsonObject(
                additionalProperties = true,
                properties = emptyMap(),
                requiredProperties = emptyList(),
            )
        }
    }

    private fun toObjectElement(schema: Schema<*>): JsonSchema.JsonObject {
        val definedProps = schema.properties.orEmpty()
        val convertedProps = definedProps.mapValues { (_, propSchema) -> toElement(propSchema) }
        val nonNullableRequired = schema.required.orEmpty().filter { propName ->
            val propSchema = definedProps[propName]?.let(::resolveSchema)
            propSchema?.nullable != true
        }
        val allowExtra = schema.additionalProperties == true || definedProps.isEmpty()
        return JsonSchema.JsonObject(
            additionalProperties = allowExtra,
            properties = convertedProps,
            requiredProperties = nonNullableRequired,
        )
    }

    private fun buildStringMatcher(schema: Schema<*>): Matcher<String> {
        var matcher: Matcher<String> = Matcher { MatcherResult(true, { "" }, { "" }) }
        schema.minLength?.let { matcher = matcher and haveMinLength(it) }
        schema.maxLength?.let { matcher = matcher and haveMaxLength(it) }
        schema.pattern?.let { matcher = matcher and contain(Regex(it)) }
        val allowedEnums = schema.enum.orEmpty().map { it.toString() }
        if (allowedEnums.isNotEmpty()) {
            matcher = matcher and beIn(allowedEnums)
        }
        schema.format?.let { matcher = matcher and formatMatcher(it) }
        return matcher
    }

    private fun formatMatcher(format: String): Matcher<String> = Matcher { value ->
        val valid = when (format) {
            "uuid" -> runCatching { UUID.fromString(value) }.isSuccess
            "date" -> runCatching { LocalDate.parse(value) }.isSuccess
            else -> true
        }
        MatcherResult(
            passed = valid,
            failureMessageFn = { "Expected valid $format format, but was '$value'" },
            negatedFailureMessageFn = { "Expected value not to match $format format" },
        )
    }

    private fun buildIntegerMatcher(schema: Schema<*>): Matcher<Long> {
        var matcher: Matcher<Long> = Matcher { MatcherResult(true, { "" }, { "" }) }
        schema.minimum?.let { matcher = matcher and beGreaterThanOrEqualTo(it.toLong()) }
        schema.maximum?.let { matcher = matcher and beLessThanOrEqualTo(it.toLong()) }
        return matcher
    }

    private fun buildNumberMatcher(schema: Schema<*>): Matcher<Double> {
        var matcher: Matcher<Double> = Matcher { MatcherResult(true, { "" }, { "" }) }
        schema.minimum?.let { matcher = matcher and beDoubleGte(it.toDouble()) }
        schema.maximum?.let { matcher = matcher and beDoubleLte(it.toDouble()) }
        return matcher
    }

    fun validateNullabilityAndRequired(
        node: JsonNode,
        rawSchema: Schema<*>,
        path: String,
    ): List<String> {
        val schema = resolveSchema(rawSchema)
        return when {
            node.isNull ->
                if (schema.nullable == true) emptyList() else listOf("$path: null is not allowed")
            node.isObject && schema.type == "object" -> {
                val missingRequired = schema.required.orEmpty()
                    .filter { !node.has(it) }
                    .map { "$path.$it: required property is missing" }
                val childErrors = schema.properties.orEmpty().flatMap { (propName, propSchema) ->
                    val child = node.get(propName)
                    if (child != null) {
                        validateNullabilityAndRequired(child, propSchema, "$path.$propName")
                    } else {
                        emptyList()
                    }
                }
                missingRequired + childErrors
            }
            node.isArray && schema.type == "array" -> {
                val itemSchema = schema.items
                if (itemSchema == null) {
                    emptyList()
                } else {
                    node.flatMapIndexed { idx, item ->
                        validateNullabilityAndRequired(item, itemSchema, "$path[$idx]")
                    }
                }
            }
            else -> emptyList()
        }
    }

    fun stripNullableNulls(node: JsonNode, rawSchema: Schema<*>): JsonNode {
        val schema = resolveSchema(rawSchema)
        return when {
            node is ObjectNode && schema.type == "object" -> stripObjectNulls(node, schema)
            node is ArrayNode && schema.type == "array" && schema.items != null ->
                schemaObjectMapper.createArrayNode().addAll(
                    node.map { stripNullableNulls(it, schema.items) },
                )
            else -> node
        }
    }

    private fun stripObjectNulls(node: ObjectNode, schema: Schema<*>): ObjectNode {
        val props = schema.properties.orEmpty()
        val copy = schemaObjectMapper.createObjectNode()
        node.fields().asSequence()
            .filterNot { (key, value) -> value.isNull && props[key]?.let(::resolveSchema)?.nullable == true }
            .forEach { (key, value) ->
                val propSchema = props[key]?.let(::resolveSchema)
                copy.set<JsonNode>(key, if (propSchema != null) stripNullableNulls(value, propSchema) else value)
            }
        return copy
    }

    private tailrec fun resolveSchema(schema: Schema<*>): Schema<*> {
        val refName = schema.`$ref`?.substringAfterLast("/")
        val nextSchema = when {
            refName != null -> requireNotNull(schemas[refName]) { "Referenced schema '$refName' not found" }
            !schema.allOf.isNullOrEmpty() -> schema.allOf.first()
            else -> null
        }
        return if (nextSchema != null) resolveSchema(nextSchema) else schema
    }
}

private fun matchesTemplatePath(actualPath: String, templatePath: String): Boolean {
    val regexPattern = templatePath.replace(Regex("""\{[^/]+}"""), "[^/]+")
    return actualPath.matches(Regex("^$regexPattern$"))
}

private fun extractPathParams(templatePath: String, actualPath: String): Map<String, String> {
    val templateSegments = templatePath.trim('/').split('/')
    val actualSegments = actualPath.trim('/').split('/')
    return if (templateSegments.size != actualSegments.size) {
        emptyMap()
    } else {
        templateSegments.zip(actualSegments)
            .filter { (tpl, _) -> tpl.startsWith("{") && tpl.endsWith("}") }
            .associate { (tpl, act) -> tpl.removeSurrounding("{", "}") to act }
    }
}

private val schemaObjectMapper = ObjectMapper()
