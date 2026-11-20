package com.example.todo

import io.swagger.v3.oas.models.media.Schema
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Generates jqwik [Arbitrary] instances directly from OpenAPI [Schema] definitions.
 */
@Suppress("TooManyFunctions")
object OpenApiJqwikArbitrary {
    private const val DEFAULT_MAX_STRING_LENGTH = 64
    private const val DEFAULT_MAX_ARRAY_ITEMS = 5
    private const val NULL_PROBABILITY = 0.2
    private const val MAX_BOUNDARY_OFFSET = 1000L
    private const val MIN_EPOCH_DAY = 0L
    private const val MAX_EPOCH_DAY = 36500L
    private const val INVALID_STRING_EXTRA = 5
    private const val INVALID_INT_SAMPLE = 999

    fun validObject(
        schema: Schema<*>,
        overrides: Map<String, Arbitrary<Any?>> = emptyMap(),
    ): Arbitrary<Map<String, Any?>> {
        val resolved = resolveAllOf(schema)
        val requiredFields = resolved.required.orEmpty().toSet()
        val entryArbitraries: List<Arbitrary<Pair<String, Any?>?>> =
            resolved.properties.orEmpty().map { (name, propSchema) ->
                val valueArb = overrides[name] ?: validValue(propSchema)
                val pairArb: Arbitrary<Pair<String, Any?>?> = valueArb.map { name to it }
                if (name in requiredFields) {
                    pairArb
                } else {
                    Arbitraries.of(true, false).flatMap { include ->
                        if (include) pairArb else Arbitraries.just(null)
                    }
                }
            }
        if (entryArbitraries.isEmpty()) {
            return Arbitraries.just(emptyMap())
        }
        return Combinators.combine(entryArbitraries).`as` { entries ->
            entries.filterNotNull().toMap(linkedMapOf())
        }
    }

    fun invalidObject(
        schema: Schema<*>,
        overrides: Map<String, Arbitrary<Any?>> = emptyMap(),
    ): Arbitrary<Map<String, Any?>> {
        val resolved = resolveAllOf(schema)
        val baseArb = validObject(resolved, overrides)
        val requiredFields = resolved.required.orEmpty().filter { it !in overrides }
        val invalidFieldArbs = resolved.properties.orEmpty()
            .filterKeys { it !in overrides }
            .mapNotNull { (name, propSchema) ->
                invalidValue(propSchema)?.let { badArb -> name to badArb }
            }

        val mutators = mutableListOf<Arbitrary<(MutableMap<String, Any?>) -> Unit>>()
        for (field in requiredFields) {
            mutators.add(Arbitraries.just { map -> map.remove(field) })
        }
        for ((name, badArb) in invalidFieldArbs) {
            mutators.add(badArb.map { badValue -> { map -> map[name] = badValue } })
        }
        if (mutators.isEmpty()) {
            return baseArb
        }
        return Combinators.combine(baseArb, Arbitraries.oneOf(mutators)).`as` { base, mutate ->
            base.toMutableMap().also(mutate)
        }
    }

    fun validValue(schema: Schema<*>): Arbitrary<Any?> {
        val resolved = resolveAllOf(schema)
        val baseArb: Arbitrary<Any?> = when {
            !resolved.enum.isNullOrEmpty() -> Arbitraries.of(resolved.enum.map { it.toString() })
            else -> validByType(resolved)
        }
        return if (resolved.nullable == true) {
            baseArb.injectNull(NULL_PROBABILITY)
        } else {
            baseArb
        }
    }

    private fun validByType(schema: Schema<*>): Arbitrary<Any?> = when (schema.type) {
        "string" -> validString(schema).map { it }
        "integer" -> validInteger(schema).map { it }
        "number" -> validNumber(schema).map { it }
        "boolean" -> Arbitraries.of(true, false).map { it }
        "array" -> validArray(schema).map { it }
        "object" -> validObject(schema).map { it }
        else -> if (schema.properties != null) validObject(schema).map { it } else Arbitraries.just(null)
    }

    private fun validString(schema: Schema<*>): Arbitrary<String> = when (schema.format) {
        "uuid" -> Arbitraries.create { UUID.randomUUID().toString() }
        "date" -> Arbitraries.longs().between(MIN_EPOCH_DAY, MAX_EPOCH_DAY).map {
            LocalDate.ofEpochDay(it).format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        "date-time" -> Arbitraries.longs().between(MIN_EPOCH_DAY, MAX_EPOCH_DAY).map {
            LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        else -> validPlainString(schema)
    }

    private fun validPlainString(schema: Schema<*>): Arbitrary<String> {
        val minLen = (schema.minLength ?: 1).coerceAtLeast(1)
        val maxLen = (schema.maxLength ?: DEFAULT_MAX_STRING_LENGTH).coerceIn(minLen, DEFAULT_MAX_STRING_LENGTH)
        val rawArb = Arbitraries.strings().alpha().numeric().ofMinLength(minLen).ofMaxLength(maxLen)
        val pattern = schema.pattern
        return if (pattern.isNullOrBlank()) {
            rawArb
        } else {
            val regex = Regex(pattern)
            rawArb.filter { regex.matches(it) }
        }
    }

    private fun validInteger(schema: Schema<*>): Arbitrary<Number> {
        val min = schema.minimum?.toLong() ?: Int.MIN_VALUE.toLong()
        val max = schema.maximum?.toLong() ?: Int.MAX_VALUE.toLong()
        val adjustedMin = if (schema.exclusiveMinimum == true) min + 1 else min
        val adjustedMax = if (schema.exclusiveMaximum == true) max - 1 else max
        return if (schema.format == "int64") {
            val clampedMax = adjustedMax.coerceAtMost(adjustedMin + MAX_BOUNDARY_OFFSET)
            Arbitraries.longs().between(adjustedMin, clampedMax).map { it }
        } else {
            val intMin = adjustedMin.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val intMax = adjustedMax.coerceIn(intMin.toLong(), Int.MAX_VALUE.toLong()).toInt()
            Arbitraries.integers().between(intMin, intMax).map { it }
        }
    }

    private fun validNumber(schema: Schema<*>): Arbitrary<Double> {
        val min = schema.minimum?.toDouble() ?: 0.0
        val max = schema.maximum?.toDouble() ?: MAX_BOUNDARY_OFFSET.toDouble()
        return Arbitraries.doubles().between(min, max)
    }

    private fun validArray(schema: Schema<*>): Arbitrary<List<Any?>> {
        val minItems = (schema.minItems ?: 0).coerceAtLeast(0)
        val maxItems = (schema.maxItems ?: DEFAULT_MAX_ARRAY_ITEMS).coerceIn(minItems, DEFAULT_MAX_ARRAY_ITEMS)
        val itemSchema = schema.items
        val itemArb: Arbitrary<Any?> = if (itemSchema != null) {
            validValue(itemSchema)
        } else {
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(DEFAULT_MAX_STRING_LENGTH).map { it }
        }
        return itemArb.list().ofMinSize(minItems).ofMaxSize(maxItems)
    }

    fun invalidValue(schema: Schema<*>): Arbitrary<Any?>? {
        val resolved = resolveAllOf(schema)
        if (!resolved.enum.isNullOrEmpty()) {
            val enumStrings = resolved.enum.map { it.toString() }.toSet()
            return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(DEFAULT_MAX_STRING_LENGTH)
                .filter { it !in enumStrings }
                .map { it }
        }
        return when (resolved.type) {
            "string" -> invalidString(resolved)
            "integer" -> invalidInteger(resolved)
            "number" -> Arbitraries.just("not-a-number")
            "boolean" -> Arbitraries.just("not-a-boolean")
            "array" -> invalidArray(resolved)
            else -> null
        }
    }

    private fun invalidString(schema: Schema<*>): Arbitrary<Any?>? {
        val candidates = mutableListOf<Arbitrary<Any?>>()
        when (schema.format) {
            "date" -> candidates.add(Arbitraries.of("not-a-date", "2025-13-45", "2025/04/18"))
            "uuid" -> candidates.add(Arbitraries.of("not-a-uuid", "12345"))
            "date-time" -> candidates.add(Arbitraries.of("not-a-datetime", "2025-99-99T00:00:00Z"))
        }
        schema.maxLength?.let { maxLen ->
            candidates.add(
                Arbitraries.strings().alpha()
                    .ofMinLength(maxLen + 1)
                    .ofMaxLength(maxLen + INVALID_STRING_EXTRA)
                    .map { it },
            )
        }
        if (schema.pattern?.contains("\\S") == true) {
            candidates.add(Arbitraries.of("", "   ", "\t\n"))
        }
        if (candidates.isEmpty() && schema.nullable != true) {
            candidates.add(Arbitraries.just(INVALID_INT_SAMPLE))
        }
        return if (candidates.isEmpty()) null else Arbitraries.oneOf(candidates)
    }

    private fun invalidInteger(schema: Schema<*>): Arbitrary<Any?> {
        val candidates = mutableListOf<Arbitrary<Any?>>()
        schema.minimum?.let { min ->
            val below = if (schema.exclusiveMinimum == true) min else min.subtract(BigDecimal.ONE)
            candidates.add(Arbitraries.just(below.toLong()))
        }
        schema.maximum?.let { max ->
            val above = if (schema.exclusiveMaximum == true) max else max.add(BigDecimal.ONE)
            candidates.add(Arbitraries.just(above.toLong()))
        }
        candidates.add(Arbitraries.just("not-an-integer"))
        return Arbitraries.oneOf(candidates)
    }

    private fun invalidArray(schema: Schema<*>): Arbitrary<Any?>? {
        val maxItems = schema.maxItems ?: return null
        val itemSchema = schema.items
        val itemArb: Arbitrary<Any?> = if (itemSchema != null) {
            validValue(itemSchema)
        } else {
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(DEFAULT_MAX_STRING_LENGTH).map { it }
        }
        return itemArb.list().ofMinSize(maxItems + 1).ofMaxSize(maxItems + 2).map { it }
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
