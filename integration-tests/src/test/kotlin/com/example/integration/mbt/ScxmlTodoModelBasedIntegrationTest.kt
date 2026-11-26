package com.example.integration.mbt

import com.example.integration.openapi.OpenApiSchemaArbitrary
import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import com.example.integration.support.WireMockAdminClient
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.restassured.RestAssured.given
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Model-based integration test driven directly by the W3C SCXML model
 * (`docs/models/todo-lifecycle.scxml`), executed against the real containerized
 * microservices stack. Every HTTP call passes through [ContainerOpenApiSpec.validationFilter]
 * so OpenAPI contract compliance is verified at every state transition.
 */
@Tag("property")
@Suppress("TooManyFunctions")
class ScxmlTodoModelBasedIntegrationTest {

    private lateinit var authorizedSpec: RequestSpecification

    @BeforeEach
    fun setup() {
        resetEnvironment()
    }

    @Test
    fun scxmlStateTransitionsSatisfyApiBehaviorAndOpenApiContract() = runProperty {
        val choicesArb = Arb.list(Arb.int(0..MAX_CHOICE_INDEX), MIN_STEPS..MAX_STEPS)
        checkAll(PropTestConfig(iterations = mbtIterations), choicesArb) { choices ->
            resetEnvironment()
            val runtime = ScxmlRuntimeState(currentState = scxmlModel.initial)
            for (choice in choices) {
                val eligible = scxmlModel.transitionsFrom(runtime.currentState)
                    .filter { isGuardSatisfied(it, runtime) }
                if (eligible.isEmpty()) break
                val transition = eligible[choice % eligible.size]
                executeTransition(transition, runtime)
                runtime.currentState = transition.target
                assertInvariants(runtime)
            }
        }
    }

    private fun resetEnvironment() {
        SpannerDatabaseCleaner.cleanAll()
        WireMockAdminClient.resetRequests()

        val username = "scxml_${UUID.randomUUID().toString().replace("-", "").take(SHORT_SUFFIX_LEN)}"
        val password = "password123"
        given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)

        val token = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path<String>("token")

        authorizedSpec = RequestSpecBuilder()
            .setBaseUri(ContainerTestConfig.gatewayBaseUrl)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(ContainerOpenApiSpec.validationFilter)
            .build()

        // Configure deterministic maxActiveTodos matching the SCXML datamodel
        given()
            .spec(authorizedSpec)
            .body(
                ContainerOpenApiSpec.objectMapper.writeValueAsString(
                    mapOf(
                        "defaultPriority" to "MEDIUM",
                        "defaultEstimatedMinutes" to DEFAULT_ESTIMATED_MINUTES,
                        "maxActiveTodos" to INITIAL_MAX_ACTIVE_TODOS,
                    ),
                ),
            )
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
    }

    private fun isGuardSatisfied(transition: ScxmlTransition, runtime: ScxmlRuntimeState): Boolean =
        when (transition.cond) {
            null -> true
            "notExists" -> runtime.currentState == "NotCreated" || runtime.currentState == "Deleted"
            "activeCount < maxActiveTodos" -> runtime.activeCount < runtime.maxActiveTodos
            "activeCount >= maxActiveTodos" -> runtime.activeCount >= runtime.maxActiveTodos
            "idempotencyReplay", "idempotencyConflict" -> runtime.lastCreateKey != null
            "completed == false", "completed == true" -> runtime.todoId != null
            "completed == false && activeCount < maxActiveTodos" ->
                runtime.todoId != null && runtime.activeCount < runtime.maxActiveTodos
            "subtaskCount > 0" -> runtime.subtaskIds.isNotEmpty()
            else -> error("Unsupported SCXML guard condition: ${transition.cond}")
        }

    private fun executeTransition(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        when (transition.event) {
            "getUserSettings" -> executeGetUserSettings(runtime)
            "updateUserSettings" -> executeUpdateUserSettings(runtime)
            "listTodos" -> executeListTodos(runtime)
            "createTodo" -> executeCreateTodo(transition, runtime)
            "getTodo" -> executeGetTodo(transition, runtime)
            "updateTodo" -> executeUpdateTodo(transition, runtime)
            "patchTodo" -> executePatchTodo(transition, runtime)
            "deleteTodo" -> executeDeleteTodo(transition, runtime)
            "listTodoSubtasks" -> executeListSubtasks(transition, runtime)
            "createTodoSubtask" -> executeCreateSubtask(transition, runtime)
            "patchTodoSubtask" -> executePatchSubtask(runtime)
            "deleteTodoSubtask" -> executeDeleteSubtask(runtime)
            else -> error("Unhandled SCXML event: ${transition.event}")
        }
    }

    private fun executeGetUserSettings(runtime: ScxmlRuntimeState) {
        val maxActive = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path<Int>("maxActiveTodos")
        assertEquals(runtime.maxActiveTodos, maxActive)
    }

    private fun executeUpdateUserSettings(runtime: ScxmlRuntimeState) {
        val nextMax = if (runtime.maxActiveTodos == INITIAL_MAX_ACTIVE_TODOS) {
            ALT_MAX_ACTIVE_TODOS
        } else {
            INITIAL_MAX_ACTIVE_TODOS
        }
        given()
            .spec(authorizedSpec)
            .body(
                ContainerOpenApiSpec.objectMapper.writeValueAsString(
                    mapOf(
                        "defaultPriority" to "HIGH",
                        "defaultEstimatedMinutes" to DEFAULT_ESTIMATED_MINUTES,
                        "maxActiveTodos" to nextMax,
                    ),
                ),
            )
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
        runtime.maxActiveTodos = nextMax
    }

    private fun executeListTodos(runtime: ScxmlRuntimeState) {
        val ids = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .jsonPath()
            .getList<String>("id")
        val expectedSize = if (runtime.currentState == "Active" || runtime.currentState == "Completed") 1 else 0
        assertEquals(expectedSize, ids.size)
    }

    private fun executeCreateTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        when (transition.cond) {
            "idempotencyReplay" -> executeCreateTodoReplay(runtime)
            "idempotencyConflict" -> executeCreateTodoConflict(runtime)
            "activeCount >= maxActiveTodos" -> executeCreateTodoLimitExceeded()
            else -> executeCreateTodoNew(runtime)
        }
    }

    private fun executeCreateTodoReplay(runtime: ScxmlRuntimeState) {
        val replayedId = given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", requireNotNull(runtime.lastCreateKey))
            .body(requireNotNull(runtime.lastCreateBody))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        assertEquals(runtime.todoId, replayedId)
    }

    private fun executeCreateTodoConflict(runtime: ScxmlRuntimeState) {
        val conflictingBody = ContainerOpenApiSpec.objectMapper.writeValueAsString(
            mapOf("title" to "conflicting-${UUID.randomUUID()}"),
        )
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", requireNotNull(runtime.lastCreateKey))
            .body(conflictingBody)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CONFLICT)
    }

    private fun executeCreateTodoLimitExceeded() {
        val body = ContainerOpenApiSpec.objectMapper.writeValueAsString(
            mapOf("title" to "limit-exceeded-${UUID.randomUUID()}"),
        )
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(body)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_BAD_REQUEST)
    }

    private fun executeCreateTodoNew(runtime: ScxmlRuntimeState) {
        val payload = sampleCreateTodoPayload()
        val body = ContainerOpenApiSpec.objectMapper.writeValueAsString(payload)
        val key = UUID.randomUUID().toString()
        val createdId = given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", key)
            .body(body)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        assertNotNull(createdId)
        runtime.todoId = createdId
        runtime.activeCount = 1
        runtime.lastCreateKey = key
        runtime.lastCreateBody = body
    }

    private fun executeGetTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_OK
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus)
            .extract()
        if (expectedStatus == HTTP_OK) {
            assertEquals(runtime.currentState == "Completed", response.path<Boolean>("completed"))
        }
    }

    private fun executeUpdateTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val desiredCompleted = transition.cond == "completed == true"
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_OK
        val body = ContainerOpenApiSpec.objectMapper.writeValueAsString(
            mapOf("title" to "Updated Todo", "completed" to desiredCompleted),
        )
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .put("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus)
        if (expectedStatus == HTTP_OK) {
            runtime.activeCount = if (desiredCompleted) 0 else 1
        }
    }

    private fun executePatchTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val desiredCompleted = transition.cond == "completed == true"
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_OK
        val body = ContainerOpenApiSpec.objectMapper.writeValueAsString(mapOf("completed" to desiredCompleted))
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .patch("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus)
        if (expectedStatus == HTTP_OK) {
            runtime.activeCount = if (desiredCompleted) 0 else 1
        }
    }

    private fun executeDeleteTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_NO_CONTENT
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus)
        if (expectedStatus == HTTP_NO_CONTENT) {
            runtime.activeCount = 0
            runtime.subtaskIds.clear()
        }
    }

    private fun executeListSubtasks(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_OK
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$targetId/subtasks")
            .then()
            .statusCode(expectedStatus)
            .extract()
        if (expectedStatus == HTTP_OK) {
            assertEquals(runtime.subtaskIds.size, response.jsonPath().getList<String>("id").size)
        }
    }

    private fun executeCreateSubtask(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HTTP_NOT_FOUND else HTTP_CREATED
        val response = given()
            .spec(authorizedSpec)
            .body(
                ContainerOpenApiSpec.objectMapper.writeValueAsString(
                    mapOf("title" to "Subtask ${runtime.subtaskIds.size + 1}"),
                ),
            )
            .`when`()
            .post("/api/todos/$targetId/subtasks")
            .then()
            .statusCode(expectedStatus)
            .extract()
        if (expectedStatus == HTTP_CREATED) {
            runtime.subtaskIds.add(response.path("id"))
        }
    }

    private fun executePatchSubtask(runtime: ScxmlRuntimeState) {
        val todoId = requireNotNull(runtime.todoId)
        val subtaskId = runtime.subtaskIds.first()
        given()
            .spec(authorizedSpec)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(mapOf("completed" to true)))
            .`when`()
            .patch("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HTTP_OK)
    }

    private fun executeDeleteSubtask(runtime: ScxmlRuntimeState) {
        val todoId = requireNotNull(runtime.todoId)
        val subtaskId = runtime.subtaskIds.removeAt(0)
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HTTP_NO_CONTENT)
    }

    private fun assertInvariants(runtime: ScxmlRuntimeState) {
        val activeIds = given()
            .spec(authorizedSpec)
            .queryParam("completed", false)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .jsonPath()
            .getList<String>("id")
        assertEquals(runtime.activeCount, activeIds.size)
    }

    private fun sampleCreateTodoPayload(): Map<String, Any?> {
        val schema = ContainerOpenApiSpec.requireSchema("CreateTodoRequest")
        val overrides = mapOf<String, Arb<Any?>>(
            "projectId" to Arb.constant(null),
            "assigneeId" to Arb.constant(null),
        )
        return OpenApiSchemaArbitrary.validObject(schema, overrides).sample(
            io.kotest.property.RandomSource.default(),
        ).value
    }

    private fun runProperty(block: suspend () -> Unit) {
        val latch = CountDownLatch(1)
        var outcome: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    outcome = result
                    latch.countDown()
                }
            },
        )
        latch.await()
        outcome?.getOrThrow()
    }

    private data class ScxmlRuntimeState(
        var currentState: String,
        var maxActiveTodos: Int = INITIAL_MAX_ACTIVE_TODOS,
        var activeCount: Int = 0,
        var todoId: String? = null,
        var lastCreateKey: String? = null,
        var lastCreateBody: String? = null,
        val subtaskIds: MutableList<String> = mutableListOf(),
    )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NO_CONTENT = 204
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val SHORT_SUFFIX_LEN = 8
        const val DEFAULT_MBT_ITERATIONS = 15
        const val MIN_STEPS = 5
        const val MAX_STEPS = 12
        const val MAX_CHOICE_INDEX = 100
        const val INITIAL_MAX_ACTIVE_TODOS = 2
        const val ALT_MAX_ACTIVE_TODOS = 1
        const val DEFAULT_ESTIMATED_MINUTES = 30
        const val SCXML_MODEL_PATH = "docs/models/todo-lifecycle.scxml"

        val mbtIterations: Int =
            System.getenv("MBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_MBT_ITERATIONS

        val scxmlModel: ScxmlModel = ScxmlModelLoader.load(SCXML_MODEL_PATH)
    }
}
