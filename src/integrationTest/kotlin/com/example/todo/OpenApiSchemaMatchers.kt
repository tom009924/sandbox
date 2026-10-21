@file:Suppress("MatchingDeclarationName")

package com.example.todo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

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
        val apiResponse = requireNotNull(operation?.responses?.get(response.statusCode.toString())) {
            "Undocumented response: $method ${requestSpec.derivedPath} -> ${response.statusCode}"
        }
        val contentType = response.contentType.substringBefore(";").trim()
        val schema = apiResponse.content?.let { it[contentType] ?: it["application/json"] }?.schema
        if (schema != null) {
            assertEquals(emptyList<String>(), validateOpenApiSchema(response.body.asString(), schema))
        }
        return response
    }
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
            node.fieldNames().asSequence().filter { it !in props }.map { "$path.$it: unexpected property" }.toList()
        } else {
            emptyList()
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
        val text = node.asText()
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

