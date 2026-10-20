package com.example.todo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.Schema
import java.time.LocalDate
import java.util.UUID

fun matchOpenApiSchema(schemaName: String, openApi: OpenAPI): Matcher<String> =
    matchOpenApiSchema(requireNotNull(openApi.components?.schemas?.get(schemaName)), openApi)

fun matchOpenApiSchema(schema: Schema<*>, openApi: OpenAPI): Matcher<String> = Matcher { actualJson ->
    val schemas = openApi.components?.schemas.orEmpty()
    val errors = runCatching { schemaObjectMapper.readTree(actualJson) }.fold(
        onSuccess = { validateNode(it, schema, schemas, "$") },
        onFailure = { listOf("$: Invalid JSON (${it.message})") },
    )
    MatcherResult(
        passed = errors.isEmpty(),
        failureMessageFn = { "OpenAPI schema mismatches:\n" + errors.joinToString("\n") { " - $it" } },
        negatedFailureMessageFn = { "Expected JSON not to match OpenAPI schema, but it matched." },
    )
}

class OpenApiValidationFilter(private val openApi: OpenAPI) : Filter {
    override fun filter(
        requestSpec: FilterableRequestSpecification,
        responseSpec: FilterableResponseSpecification,
        ctx: FilterContext,
    ): Response {
        val response = ctx.next(requestSpec, responseSpec)
        val method = HttpMethod.valueOf(requestSpec.method.uppercase())
        val operation = openApi.paths.orEmpty().entries
            .firstOrNull { (tpl, _) -> matchesPath(requestSpec.derivedPath, tpl) }
            ?.value?.readOperationsMap()?.get(method)
            .shouldNotBeNull()
        val apiResponse = operation.responses[response.statusCode.toString()].shouldNotBeNull()
        val mediaType = apiResponse.content?.let {
            it[response.contentType.substringBefore(";").trim()] ?: it["application/json"]
        }
        if (mediaType?.schema != null) {
            response.body.asString() should matchOpenApiSchema(mediaType.schema, openApi)
        }
        return response
    }
}

private fun validateNode(
    node: JsonNode,
    rawSchema: Schema<*>,
    schemas: Map<String, Schema<*>>,
    path: String,
): List<String> {
    val schema = rawSchema.resolve(schemas)
    return if (node.isNull) {
        if (schema.nullable == true) emptyList() else listOf("$path: null is not allowed")
    } else {
        when (schema.type) {
            "object" -> validateObject(node, schema, schemas, path)
            "array" -> validateArray(node, schema, schemas, path)
            "string" -> validateString(node, schema, path)
            "integer" -> validateInteger(node, schema, path)
            "boolean" -> if (node.isBoolean) emptyList() else listOf("$path: expected boolean")
            else -> emptyList()
        }
    }
}

private fun validateObject(
    node: JsonNode,
    schema: Schema<*>,
    schemas: Map<String, Schema<*>>,
    path: String,
): List<String> =
    if (!node.isObject) {
        listOf("$path: expected object, got ${node.nodeType}")
    } else {
        val props = schema.properties.orEmpty()
        val missing = schema.required.orEmpty().filter { !node.has(it) }.map { "$path.$it: required" }
        val extra = if (schema.additionalProperties != true && props.isNotEmpty()) {
            node.fieldNames().asSequence().filter { it !in props }.map { "$path.$it: unexpected property" }.toList()
        } else {
            emptyList()
        }
        val childErrors = props.flatMap { (name, propSchema) ->
            if (node.has(name)) validateNode(node.get(name), propSchema, schemas, "$path.$name") else emptyList()
        }
        missing + extra + childErrors
    }

private fun validateArray(
    node: JsonNode,
    schema: Schema<*>,
    schemas: Map<String, Schema<*>>,
    path: String,
): List<String> =
    if (!node.isArray) {
        listOf("$path: expected array, got ${node.nodeType}")
    } else {
        schema.items?.let { itemSchema ->
            node.flatMapIndexed { idx, item -> validateNode(item, itemSchema, schemas, "$path[$idx]") }
        }.orEmpty()
    }

private fun validateString(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isTextual) {
        listOf("$path: expected string, got ${node.nodeType}")
    } else {
        val text = node.asText()
        val enums = schema.enum.orEmpty().map { it.toString() }
        buildList {
            schema.pattern?.let { if (!Regex(it).containsMatchIn(text)) add("$path: pattern '$it' mismatch") }
            if (enums.isNotEmpty() && text !in enums) add("$path: '$text' not in $enums")
            when (schema.format) {
                "uuid" -> runCatching { UUID.fromString(text) }.onFailure { add("$path: invalid uuid") }
                "date" -> runCatching { LocalDate.parse(text) }.onFailure { add("$path: invalid date") }
            }
        }
    }

private fun validateInteger(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isIntegralNumber) {
        listOf("$path: expected integer, got ${node.nodeType}")
    } else {
        val min = schema.minimum
        if (min != null && node.decimalValue() < min) listOf("$path: below minimum $min") else emptyList()
    }

private tailrec fun Schema<*>.resolve(schemas: Map<String, Schema<*>>): Schema<*> {
    val refName = `$ref`?.substringAfterLast("/")
    val next = when {
        refName != null -> requireNotNull(schemas[refName]) { "Schema '$refName' not found" }
        !allOf.isNullOrEmpty() -> allOf.first()
        else -> null
    }
    return if (next != null) next.resolve(schemas) else this
}

private val schemaObjectMapper = ObjectMapper()
