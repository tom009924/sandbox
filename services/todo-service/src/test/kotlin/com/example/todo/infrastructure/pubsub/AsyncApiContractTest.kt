package com.example.todo.infrastructure.pubsub

import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class AsyncApiContractTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `serialized CREATED and UPDATED events conform to AsyncAPI TodoEvent schema`() {
        val spec = loadAsyncApiSpec()
        val schemas = getSchemas(spec)
        val todoEventSchema = schemas.getValue("TodoEvent")
        val todoPayloadSchema = schemas.getValue("TodoPayload")

        listOf(TodoEventType.CREATED, TodoEventType.UPDATED).forEach { eventType ->
            val event = TodoEvent(
                eventId = UUID.randomUUID(),
                eventType = eventType,
                timestamp = Instant.parse("2025-04-18T12:00:00Z"),
                todoId = UUID.randomUUID(),
                todo = Todo(
                    id = UUID.randomUUID(),
                    userId = UUID.randomUUID(),
                    title = "Contract test todo",
                    completed = false,
                    description = "Plaintext description",
                    dueDate = LocalDate.parse("2025-04-19"),
                    priority = TodoPriority.HIGH,
                    estimatedMinutes = 30,
                    tags = listOf("asyncapi", "contract"),
                ),
            )

            val json = objectMapper.writeValueAsString(event)
            val node = objectMapper.readTree(json)

            assertMatchesObjectSchema(node, todoEventSchema, schemas, "TodoEvent($eventType)")
            assertFalse(node.get("todo").isNull, "todo should not be null for $eventType")
            assertMatchesObjectSchema(node.get("todo"), todoPayloadSchema, schemas, "TodoPayload($eventType)")
        }
    }

    @Test
    fun `serialized DELETED event with null todo conforms to AsyncAPI TodoEvent schema`() {
        val spec = loadAsyncApiSpec()
        val schemas = getSchemas(spec)
        val todoEventSchema = schemas.getValue("TodoEvent")

        val event = TodoEvent(
            eventId = UUID.randomUUID(),
            eventType = TodoEventType.DELETED,
            timestamp = Instant.parse("2025-04-18T12:00:00Z"),
            todoId = UUID.randomUUID(),
            todo = null,
        )

        val json = objectMapper.writeValueAsString(event)
        val node = objectMapper.readTree(json)

        assertMatchesObjectSchema(node, todoEventSchema, schemas, "TodoEvent(DELETED)")
        assertTrue(node.get("todo").isNull, "todo should be null for DELETED")
    }

    @Test
    fun `serialized TodoPayload with nullable fields omitted conforms to AsyncAPI TodoPayload schema`() {
        val spec = loadAsyncApiSpec()
        val schemas = getSchemas(spec)
        val todoPayloadSchema = schemas.getValue("TodoPayload")

        val todo = Todo(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            title = "Minimal todo",
            completed = true,
            description = null,
            dueDate = null,
            priority = TodoPriority.LOW,
            estimatedMinutes = null,
            tags = emptyList(),
        )

        val json = objectMapper.writeValueAsString(todo)
        val node = objectMapper.readTree(json)

        assertMatchesObjectSchema(node, todoPayloadSchema, schemas, "TodoPayload(minimal)")
    }

    @Test
    fun `domain enums match AsyncAPI enum definitions`() {
        val spec = loadAsyncApiSpec()
        val schemas = getSchemas(spec)

        val todoEventProps = asMap(schemas.getValue("TodoEvent")["properties"])
        val specEventTypes = asStringList(asMap(todoEventProps["eventType"])["enum"]).toSet()
        val domainEventTypes = TodoEventType.entries.map { it.name }.toSet()
        assertEquals(specEventTypes, domainEventTypes, "TodoEventType enum must match AsyncAPI TodoEvent.eventType")

        val messages = asMap(asMap(spec["components"])["messages"])
        val messageHeaders = asMap(asMap(messages["TodoEventMessage"])["headers"])
        val headerProps = asMap(messageHeaders["properties"])
        val headerEventTypes = asStringList(asMap(headerProps["eventType"])["enum"]).toSet()
        assertEquals(headerEventTypes, domainEventTypes, "TodoEventMessage header eventType must match domain enum")

        val todoPayloadProps = asMap(schemas.getValue("TodoPayload")["properties"])
        val specPriorities = asStringList(asMap(todoPayloadProps["priority"])["enum"]).toSet()
        val domainPriorities = TodoPriority.entries.map { it.name }.toSet()
        assertEquals(specPriorities, domainPriorities, "TodoPriority enum must match AsyncAPI TodoPayload.priority")
    }

    private fun assertMatchesObjectSchema(
        node: JsonNode,
        schema: Map<String, Any?>,
        allSchemas: Map<String, Map<String, Any?>>,
        context: String,
    ) {
        assertTrue(node.isObject, "$context must be a JSON object")

        val required = asStringList(schema["required"])
        required.forEach { field ->
            assertTrue(node.has(field), "$context is missing required field '$field'")
            assertFalse(node.get(field).isNull, "$context required field '$field' must not be null")
        }

        val properties = asMap(schema["properties"])
        properties.forEach { (propName, rawPropSchema) ->
            if (!node.has(propName)) {
                return@forEach
            }
            val rawMap = asMap(rawPropSchema)
            val oneOfList = (rawMap["oneOf"] as? List<*>)?.map { asMap(it) }.orEmpty()
            val effectiveRaw = oneOfList.firstOrNull { it["type"] != "null" } ?: rawMap
            val propSchema = resolveSchema(effectiveRaw, allSchemas)
            val propNode = node.get(propName)
            val rawTypes = (propSchema["type"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val nullable =
                propSchema["nullable"] == true ||
                    rawMap["nullable"] == true ||
                    oneOfList.any { it["type"] == "null" } ||
                    "null" in rawTypes
            if (propNode.isNull) {
                assertTrue(nullable, "$context.$propName is null but not marked nullable in AsyncAPI schema")
                return@forEach
            }
            assertMatchesPropertyType(propNode, propSchema, "$context.$propName")
        }
    }

    private fun assertMatchesPropertyType(
        node: JsonNode,
        schema: Map<String, Any?>,
        path: String,
    ) {
        val rawType = schema["type"]
        val resolvedType = when (rawType) {
            is String -> rawType
            is List<*> -> rawType.mapNotNull { it as? String }.firstOrNull { it != "null" }
            else -> null
        }
        when (resolvedType) {
            "string" -> {
                assertTrue(node.isString, "$path must be a string")
                val text = node.asString()
                val enumValues = schema["enum"]
                if (enumValues != null) {
                    val allowed = asStringList(enumValues)
                    assertTrue(allowed.contains(text), "$path value '$text' must be one of $allowed")
                }
                when (schema["format"] as? String) {
                    "uuid" -> UUID.fromString(text)
                    "date-time" -> Instant.parse(text)
                    "date" -> LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                }
            }
            "boolean" -> assertTrue(node.isBoolean, "$path must be a boolean")
            "integer" -> assertTrue(node.isIntegralNumber, "$path must be an integer")
            "array" -> {
                assertTrue(node.isArray, "$path must be an array")
                val itemsSchema = asMap(schema["items"])
                node.forEachIndexed { index, item ->
                    assertMatchesPropertyType(item, itemsSchema, "$path[$index]")
                }
            }
            "object" -> assertTrue(node.isObject, "$path must be an object")
            else -> error("Unsupported schema type '$resolvedType' at $path")
        }
    }

    private fun resolveSchema(
        schema: Map<String, Any?>,
        allSchemas: Map<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        val ref = schema["\$ref"] as? String ?: return schema
        val schemaName = ref.removePrefix("#/components/schemas/")
        return allSchemas.getValue(schemaName)
    }

    private fun loadAsyncApiSpec(): Map<String, Any?> {
        val specPath = Path.of("docs/asyncapi.yaml")
        assertTrue(Files.exists(specPath), "docs/asyncapi.yaml must exist")
        val content = Files.readString(specPath)
        val parsed = Yaml().load<Map<String, Any?>>(content)
        assertNotNull(parsed, "docs/asyncapi.yaml must parse as YAML map")
        return parsed
    }

    private fun getSchemas(spec: Map<String, Any?>): Map<String, Map<String, Any?>> {
        val components = asMap(spec["components"])
        val schemas = asMap(components["schemas"])
        return schemas.mapValues { (_, value) -> asMap(value) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    private fun asStringList(value: Any?): List<String> =
        (value as? List<*>)?.map { it.toString() } ?: emptyList()
}
