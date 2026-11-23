package com.example.integration.openapi

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.swagger.v3.oas.models.media.Schema
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates Kotest [Arb] instances directly from OpenAPI [Schema] definitions.
 */
@Suppress("TooManyFunctions")
object OpenApiSchemaArbitrary {
    private const val DEFAULT_MAX_STRING_LENGTH = 64
    private const val DEFAULT_MAX_ARRAY_ITEMS = 5
    private const val NULL_PROBABILITY = 0.2
    private const val MAX_BOUNDARY_OFFSET = 1000L
    private const val INVALID_STRING_EXTRA = 5
    private const val INVALID_INT_SAMPLE = 999

    fun validObject(
        schema: Schema<*>,
        overrides: Map<String, Arb<Any?>> = emptyMap(),
    ): Arb<Map<String, Any?>> = arbitrary {
        val resolved = resolveAllOf(schema)
        val requiredFields = resolved.required.orEmpty().toSet()
        val properties = resolved.properties.orEmpty()
        val result = linkedMapOf<String, Any?>()
        for ((name, propSchema) in properties) {
            val include = name in requiredFields || Arb.boolean().bind()
            if (include) {
                val arb = overrides[name] ?: validValue(propSchema)
                result[name] = arb.bind()
            }
        }
        result
    }

    fun invalidObject(
        schema: Schema<*>,
        overrides: Map<String, Arb<Any?>> = emptyMap(),
    ): Arb<Map<String, Any?>> {
        val resolved = resolveAllOf(schema)
        val requiredFields = resolved.required.orEmpty().filter { it !in overrides }
        val invalidFieldArbs = resolved.properties.orEmpty()
            .filterKeys { it !in overrides }
            .mapNotNull { (name, propSchema) ->
                invalidValue(propSchema)?.let { badArb -> name to badArb }
            }
        return arbitrary {
            val base = validObject(resolved, overrides).bind().toMutableMap()
            val canRemoveRequired = requiredFields.isNotEmpty()
            val canCorruptField = invalidFieldArbs.isNotEmpty()
            when {
                canRemoveRequired && (!canCorruptField || Arb.boolean().bind()) -> {
                    base.remove(Arb.element(requiredFields).bind())
                }
                canCorruptField -> {
                    val (fieldName, badArb) = Arb.element(invalidFieldArbs).bind()
                    base[fieldName] = badArb.bind()
                }
            }
            base
        }
    }

    fun validValue(schema: Schema<*>): Arb<Any?> {
        val resolved = resolveAllOf(schema)
        val baseArb: Arb<Any?> = when {
            !resolved.enum.isNullOrEmpty() -> Arb.element(resolved.enum.map { it.toString() })
            else -> validByType(resolved)
        }
        return if (resolved.nullable == true) {
            baseArb.orNull(NULL_PROBABILITY)
        } else {
            baseArb
        }
    }

    private fun validByType(schema: Schema<*>): Arb<Any?> = when (schema.type) {
        "string" -> validString(schema)
        "integer" -> validInteger(schema)
        "number" -> validNumber(schema)
        "boolean" -> Arb.boolean()
        "array" -> validArray(schema)
        "object" -> validObject(schema)
        else -> if (schema.properties != null) validObject(schema) else Arb.constant(null)
    }

    private fun validString(schema: Schema<*>): Arb<String> = when (schema.format) {
        "uuid" -> Arb.uuid().map { it.toString() }
        "date" -> Arb.localDate().map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) }
        "date-time" -> Arb.localDate().map {
            it.atStartOfDay(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        else -> validPlainString(schema)
    }

    private fun validPlainString(schema: Schema<*>): Arb<String> {
        val minLen = (schema.minLength ?: 1).coerceAtLeast(1)
        val maxLen = (schema.maxLength ?: DEFAULT_MAX_STRING_LENGTH).coerceIn(minLen, DEFAULT_MAX_STRING_LENGTH)
        val rawArb = Arb.string(minSize = minLen, maxSize = maxLen, codepoints = Codepoint.alphanumeric())
        val pattern = schema.pattern
        return if (pattern.isNullOrBlank()) {
            rawArb
        } else {
            val regex = Regex(pattern)
            rawArb.filter { regex.matches(it) }
        }
    }

    private fun validInteger(schema: Schema<*>): Arb<Number> {
        val min = schema.minimum?.toLong() ?: Int.MIN_VALUE.toLong()
        val max = schema.maximum?.toLong() ?: Int.MAX_VALUE.toLong()
        val adjustedMin = if (schema.exclusiveMinimum == true) min + 1 else min
        val adjustedMax = if (schema.exclusiveMaximum == true) max - 1 else max
        return if (schema.format == "int64") {
            val clampedMax = adjustedMax.coerceAtMost(adjustedMin + MAX_BOUNDARY_OFFSET)
            Arb.long(adjustedMin..clampedMax)
        } else {
            val intMin = adjustedMin.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val intMax = adjustedMax.coerceIn(intMin.toLong(), Int.MAX_VALUE.toLong()).toInt()
            Arb.int(intMin..intMax)
        }
    }

    private fun validNumber(schema: Schema<*>): Arb<Double> {
        val min = schema.minimum?.toDouble() ?: 0.0
        val max = schema.maximum?.toDouble() ?: MAX_BOUNDARY_OFFSET.toDouble()
        return Arb.double(min, max)
    }

    private fun validArray(schema: Schema<*>): Arb<List<Any?>> {
        val minItems = (schema.minItems ?: 0).coerceAtLeast(0)
        val maxItems = (schema.maxItems ?: DEFAULT_MAX_ARRAY_ITEMS).coerceIn(minItems, DEFAULT_MAX_ARRAY_ITEMS)
        val itemSchema = schema.items
        val itemArb = if (itemSchema != null) validValue(itemSchema) else Arb.string(1, DEFAULT_MAX_STRING_LENGTH)
        return Arb.list(itemArb, minItems..maxItems)
    }

    fun invalidValue(schema: Schema<*>): Arb<Any?>? {
        val resolved = resolveAllOf(schema)
        if (!resolved.enum.isNullOrEmpty()) {
            val enumStrings = resolved.enum.map { it.toString() }.toSet()
            return Arb.string(1..DEFAULT_MAX_STRING_LENGTH, Codepoint.alphanumeric())
                .filter { it !in enumStrings }
        }
        return when (resolved.type) {
            "string" -> invalidString(resolved)
            "integer" -> invalidInteger(resolved)
            "number" -> Arb.constant("not-a-number")
            "boolean" -> Arb.constant("not-a-boolean")
            "array" -> invalidArray(resolved)
            else -> null
        }
    }

    private fun invalidString(schema: Schema<*>): Arb<Any?>? {
        val candidates = mutableListOf<Arb<Any?>>()
        when (schema.format) {
            "date" -> candidates.add(Arb.element("not-a-date", "2025-13-45", "2025/04/18"))
            "uuid" -> candidates.add(Arb.element("not-a-uuid", "12345", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
            "date-time" -> candidates.add(Arb.element("not-a-datetime", "2025-99-99T00:00:00Z"))
        }
        schema.maxLength?.let { maxLen ->
            candidates.add(
                Arb.string(
                    minSize = maxLen + 1,
                    maxSize = maxLen + INVALID_STRING_EXTRA,
                    codepoints = Codepoint.alphanumeric(),
                ),
            )
        }
        if (schema.pattern?.contains("\\S") == true) {
            candidates.add(Arb.element("", "   ", "\t\n"))
        }
        if (candidates.isEmpty() && schema.nullable != true) {
            candidates.add(Arb.constant(INVALID_INT_SAMPLE))
        }
        return if (candidates.isEmpty()) null else Arb.choice(candidates)
    }

    private fun invalidInteger(schema: Schema<*>): Arb<Any?> {
        val candidates = mutableListOf<Arb<Any?>>()
        schema.minimum?.let { min ->
            val below = if (schema.exclusiveMinimum == true) min else min.subtract(BigDecimal.ONE)
            candidates.add(Arb.constant(below.toLong()))
        }
        schema.maximum?.let { max ->
            val above = if (schema.exclusiveMaximum == true) max else max.add(BigDecimal.ONE)
            candidates.add(Arb.constant(above.toLong()))
        }
        candidates.add(Arb.constant("not-an-integer"))
        return Arb.choice(candidates)
    }

    private fun invalidArray(schema: Schema<*>): Arb<Any?>? {
        val maxItems = schema.maxItems ?: return null
        val itemSchema = schema.items
        val itemArb = if (itemSchema != null) validValue(itemSchema) else Arb.string(1, DEFAULT_MAX_STRING_LENGTH)
        return Arb.list(itemArb, (maxItems + 1)..(maxItems + 2))
    }

    private fun resolveAllOf(schema: Schema<*>): Schema<*> {
        if (schema.allOf.isNullOrEmpty()) return schema
        val merged = Schema<Any>()
        val allRequired = mutableListOf<String>()
        val allProps = linkedMapOf<String, Schema<*>>()
        for (part in schema.allOf) {
            mergeConstraints(merged, part)
            part.required?.let { allRequired.addAll(it) }
            part.properties?.let { allProps.putAll(it) }
        }
        if (allRequired.isNotEmpty()) merged.required = allRequired
        if (allProps.isNotEmpty()) merged.properties = allProps
        if (schema.nullable != null) merged.nullable = schema.nullable
        return merged
    }

    private fun mergeConstraints(target: Schema<Any>, source: Schema<*>) {
        target.type = target.type ?: source.type
        target.format = target.format ?: source.format
        target.enum = target.enum ?: source.enum
        target.nullable = target.nullable ?: source.nullable
        target.minimum = target.minimum ?: source.minimum
        target.maximum = target.maximum ?: source.maximum
        target.minLength = target.minLength ?: source.minLength
        target.maxLength = target.maxLength ?: source.maxLength
        target.pattern = target.pattern ?: source.pattern
        target.items = target.items ?: source.items
        target.minItems = target.minItems ?: source.minItems
        target.maxItems = target.maxItems ?: source.maxItems
    }
}
