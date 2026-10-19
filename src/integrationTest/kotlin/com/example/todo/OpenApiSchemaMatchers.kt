package com.example.todo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
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
    val schemas = openApi.components?.schemas.orEmpty()
    val errors = runCatching { schemaObjectMapper.readTree(actualJson) }
        .fold(
            onSuccess = { rootNode ->
                if (rootNode == null || rootNode.isMissingNode) {
                    listOf("$: JSON body is empty or missing")
                } else {
                    OpenApiSchemaValidator(schemas).validate(rootNode, schema, "$")
                }
            },
            onFailure = { ex -> listOf("$: Invalid JSON (${ex.message})") },
        )

    MatcherResult(
        passed = errors.isEmpty(),
        failureMessageFn = {
            "JSON did not match OpenAPI schema:\n" + errors.joinToString("\n") { " - $it" }
        },
        negatedFailureMessageFn = {
            "Expected JSON not to match OpenAPI schema, but it matched."
        },
    )
}

class OpenApiValidationFilter(
    private val openApi: OpenAPI,
    private val validateRequest: Boolean = true,
) : Filter {
    private val schemas = openApi.components?.schemas.orEmpty()
    private val validator = OpenApiSchemaValidator(schemas)

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
            if (value == null) {
                if (parameter.required == true) {
                    listOf("Missing required ${parameter.`in`} parameter '${parameter.name}'")
                } else {
                    emptyList()
                }
            } else {
                val paramSchema = parameter.schema
                if (paramSchema != null) {
                    validator.validate(
                        schemaObjectMapper.valueToTree(value),
                        paramSchema,
                        "${parameter.`in`}.${parameter.name}",
                    )
                } else {
                    emptyList()
                }
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

private class OpenApiSchemaValidator(
    private val schemas: Map<String, Schema<*>>,
) {
    fun validate(node: JsonNode, rawSchema: Schema<*>, path: String): List<String> {
        val schema = resolveSchema(rawSchema)
        return if (node.isNull) {
            if (schema.nullable == true) emptyList() else listOf("$path: null is not allowed")
        } else {
            validateNonNullNode(node, schema, path)
        }
    }

    private fun validateNonNullNode(node: JsonNode, schema: Schema<*>, path: String): List<String> =
        when (schema.type) {
            "object" -> validateObject(node, schema, path)
            "array" -> validateArray(node, schema, path)
            "string" -> validateString(node, schema, path)
            "integer" -> validateInteger(node, schema, path)
            "number" -> validateNumber(node, schema, path)
            "boolean" -> if (node.isBoolean) emptyList() else listOf("$path: expected boolean, got ${node.nodeType}")
            else -> emptyList()
        }

    private fun validateObject(node: JsonNode, schema: Schema<*>, path: String): List<String> {
        if (!node.isObject) return listOf("$path: expected object, got ${node.nodeType}")
        val errors = mutableListOf<String>()
        val definedProperties = schema.properties.orEmpty()

        schema.required.orEmpty().forEach { reqField ->
            if (!node.has(reqField)) {
                errors += "$path.$reqField: required property is missing"
            }
        }
        if (schema.additionalProperties != true && definedProperties.isNotEmpty()) {
            node.fieldNames().forEach { fieldName ->
                if (fieldName !in definedProperties) {
                    errors += "$path.$fieldName: property is not allowed by schema"
                }
            }
        }
        definedProperties.forEach { (propName, propSchema) ->
            if (node.has(propName)) {
                errors += validate(node.get(propName), propSchema, "$path.$propName")
            }
        }
        return errors
    }

    private fun validateArray(node: JsonNode, schema: Schema<*>, path: String): List<String> =
        if (!node.isArray) {
            listOf("$path: expected array, got ${node.nodeType}")
        } else {
            val itemSchema = schema.items
            if (itemSchema == null) {
                emptyList()
            } else {
                node.flatMapIndexed { index, element ->
                    validate(element, itemSchema, "$path[$index]")
                }
            }
        }

    private fun validateString(node: JsonNode, schema: Schema<*>, path: String): List<String> {
        if (!node.isTextual) return listOf("$path: expected string, got ${node.nodeType}")
        val text = node.asText()
        val errors = mutableListOf<String>()

        schema.minLength?.let { min ->
            if (text.length < min) errors += "$path: length ${text.length} is less than minLength $min"
        }
        schema.maxLength?.let { max ->
            if (text.length > max) errors += "$path: length ${text.length} is greater than maxLength $max"
        }
        schema.pattern?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(text)) {
                errors += "$path: value '$text' does not match pattern '$pattern'"
            }
        }
        val allowedEnums = schema.enum.orEmpty().map { it.toString() }
        if (allowedEnums.isNotEmpty() && text !in allowedEnums) {
            errors += "$path: value '$text' is not in enum $allowedEnums"
        }
        errors += validateStringFormat(text, schema.format, path)
        return errors
    }

    private fun validateStringFormat(text: String, format: String?, path: String): List<String> =
        when (format) {
            "uuid" -> runCatching { UUID.fromString(text) }
                .fold(onSuccess = { emptyList() }, onFailure = { listOf("$path: invalid uuid '$text'") })
            "date" -> runCatching { LocalDate.parse(text) }
                .fold(onSuccess = { emptyList() }, onFailure = { listOf("$path: invalid date '$text'") })
            else -> emptyList()
        }

    private fun validateInteger(node: JsonNode, schema: Schema<*>, path: String): List<String> =
        if (!node.isIntegralNumber) {
            listOf("$path: expected integer, got ${node.nodeType}")
        } else {
            validateNumericBounds(node, schema, path)
        }

    private fun validateNumber(node: JsonNode, schema: Schema<*>, path: String): List<String> =
        if (!node.isNumber) {
            listOf("$path: expected number, got ${node.nodeType}")
        } else {
            validateNumericBounds(node, schema, path)
        }

    private fun validateNumericBounds(node: JsonNode, schema: Schema<*>, path: String): List<String> {
        val value = node.decimalValue()
        val errors = mutableListOf<String>()
        schema.minimum?.let { min ->
            if (value < min) errors += "$path: $value is less than minimum $min"
        }
        schema.maximum?.let { max ->
            if (value > max) errors += "$path: $value is greater than maximum $max"
        }
        return errors
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
    if (templateSegments.size != actualSegments.size) return emptyMap()
    return templateSegments.zip(actualSegments)
        .filter { (tpl, _) -> tpl.startsWith("{") && tpl.endsWith("}") }
        .associate { (tpl, act) -> tpl.removeSurrounding("{", "}") to act }
}

private val schemaObjectMapper = ObjectMapper()

