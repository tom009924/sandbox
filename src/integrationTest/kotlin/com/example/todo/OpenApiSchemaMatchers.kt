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
    if (node.isNull) {
        if (schema.nullable == true) emptyList() else listOf("$path: null is not allowed")
    } else {
        when (schema.type) {
            "object" -> validateObject(node, schema, path)
            "array" -> if (!node.isArray) {
                listOf("$path: expected array")
            } else {
                node.flatMapIndexed { i, item -> validateSchema(item, schema.items, "$path[$i]") }
            }
            "string" -> validateString(node, schema, path)
            "integer" -> if (!node.isIntegralNumber) {
                listOf("$path: expected integer")
            } else if (schema.minimum != null && node.decimalValue() < schema.minimum) {
                listOf("$path: below minimum ${schema.minimum}")
            } else {
                emptyList()
            }
            "boolean" -> if (node.isBoolean) emptyList() else listOf("$path: expected boolean")
            else -> emptyList()
        }
    }

private fun validateObject(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isObject) {
        listOf("$path: expected object")
    } else {
        val props = schema.properties.orEmpty()
        val missing = schema.required.orEmpty().filter { !node.has(it) }.map { "$path.$it: required" }
        val extra = if (schema.additionalProperties != true && props.isNotEmpty()) {
            node.fieldNames().asSequence().filter { it !in props }.map { "$path.$it: unexpected" }.toList()
        } else {
            emptyList()
        }
        val children = props.flatMap { (k, v) ->
            if (node.has(k)) validateSchema(node.get(k), v, "$path.$k") else emptyList()
        }
        missing + extra + children
    }

private fun validateString(node: JsonNode, schema: Schema<*>, path: String): List<String> =
    if (!node.isTextual) {
        listOf("$path: expected string")
    } else {
        val text = node.asText()
        val enums = schema.enum.orEmpty().map { it.toString() }
        buildList {
            schema.pattern?.let { if (!Regex(it).containsMatchIn(text)) add("$path: pattern '$it' mismatch") }
            if (enums.isNotEmpty() && text !in enums) add("$path: '$text' not in $enums")
            if (schema.format == "uuid" && runCatching { UUID.fromString(text) }.isFailure) add("$path: invalid uuid")
            if (schema.format == "date" && runCatching { LocalDate.parse(text) }.isFailure) add("$path: invalid date")
        }
    }

private val objectMapper = ObjectMapper()

