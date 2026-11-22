package com.example.todo.mbt

import com.example.todo.OpenApiSchemaArbitrary
import com.example.todo.OpenApiValidationFilter
import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectMemberRepository
import com.example.todo.infrastructure.persistence.SpringDataProjectRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Model-based integration test driven directly by the W3C SCXML model
 * (`docs/models/todo-lifecycle.scxml`). Every HTTP call passes through
 * [OpenApiValidationFilter] so OpenAPI contract compliance is verified at every state transition.
 */
@Tag("property")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["identity.service.enabled=false"],
)
@Suppress("TooManyFunctions")
class ScxmlTodoModelBasedIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val springDataProjectMemberRepository: SpringDataProjectMemberRepository,
    private val springDataProjectRepository: SpringDataProjectRepository,
    private val objectMapper: ObjectMapper,
) {
    @LocalServerPort
    private var port: Int = 0

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
        todoRepository.findAll().forEach(todoRepository::delete)
        springDataProjectMemberRepository.deleteAll()
        springDataProjectRepository.deleteAll()
        userRepository.deleteAll()
        wireMockServer.resetRequests()

        val user = userRepository.save(User(username = "scxml_mbt_${UUID.randomUUID()}", passwordHash = "hash"))
        val userId = requireNotNull(user.id)
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, user.username))
        authorizedSpec = RequestSpecBuilder()
            .setPort(port)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(openApiValidationFilter)
            .build()

        // Configure deterministic maxActiveTodos matching the SCXML datamodel
        given()
            .spec(authorizedSpec)
            .body(
                objectMapper.writeValueAsString(
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
            .statusCode(HttpStatus.OK.value())
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
            .statusCode(HttpStatus.OK.value())
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
                objectMapper.writeValueAsString(
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
            .statusCode(HttpStatus.OK.value())
        runtime.maxActiveTodos = nextMax
    }

    private fun executeListTodos(runtime: ScxmlRuntimeState) {
        val ids = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
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
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path<String>("id")
        assertEquals(runtime.todoId, replayedId)
    }

    private fun executeCreateTodoConflict(runtime: ScxmlRuntimeState) {
        val conflictingBody = objectMapper.writeValueAsString(
            mapOf("title" to "conflicting-${UUID.randomUUID()}"),
        )
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", requireNotNull(runtime.lastCreateKey))
            .body(conflictingBody)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CONFLICT.value())
    }

    private fun executeCreateTodoLimitExceeded() {
        val body = objectMapper.writeValueAsString(mapOf("title" to "limit-exceeded-${UUID.randomUUID()}"))
        given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(body)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    private fun executeCreateTodoNew(runtime: ScxmlRuntimeState) {
        val payload = sampleCreateTodoPayload()
        val body = objectMapper.writeValueAsString(payload)
        val key = UUID.randomUUID().toString()
        val createdId = given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", key)
            .body(body)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
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
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.OK
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus.value())
            .extract()
        if (expectedStatus == HttpStatus.OK) {
            assertEquals(runtime.currentState == "Completed", response.path<Boolean>("completed"))
        }
    }

    private fun executeUpdateTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val desiredCompleted = transition.cond == "completed == true"
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.OK
        val body = objectMapper.writeValueAsString(
            mapOf("title" to "Updated Todo", "completed" to desiredCompleted),
        )
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .put("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus.value())
        if (expectedStatus == HttpStatus.OK) {
            runtime.activeCount = if (desiredCompleted) 0 else 1
        }
    }

    private fun executePatchTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val desiredCompleted = transition.cond == "completed == true"
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.OK
        val body = objectMapper.writeValueAsString(mapOf("completed" to desiredCompleted))
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .patch("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus.value())
        if (expectedStatus == HttpStatus.OK) {
            runtime.activeCount = if (desiredCompleted) 0 else 1
        }
    }

    private fun executeDeleteTodo(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.NO_CONTENT
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$targetId")
            .then()
            .statusCode(expectedStatus.value())
        if (expectedStatus == HttpStatus.NO_CONTENT) {
            runtime.activeCount = 0
            runtime.subtaskIds.clear()
        }
    }

    private fun executeListSubtasks(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.OK
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$targetId/subtasks")
            .then()
            .statusCode(expectedStatus.value())
            .extract()
        if (expectedStatus == HttpStatus.OK) {
            assertEquals(runtime.subtaskIds.size, response.jsonPath().getList<String>("id").size)
        }
    }

    private fun executeCreateSubtask(transition: ScxmlTransition, runtime: ScxmlRuntimeState) {
        val targetId = runtime.todoId ?: UUID.randomUUID().toString()
        val expectedStatus = if (transition.cond == "notExists") HttpStatus.NOT_FOUND else HttpStatus.CREATED
        val response = given()
            .spec(authorizedSpec)
            .body(objectMapper.writeValueAsString(mapOf("title" to "Subtask ${runtime.subtaskIds.size + 1}")))
            .`when`()
            .post("/api/todos/$targetId/subtasks")
            .then()
            .statusCode(expectedStatus.value())
            .extract()
        if (expectedStatus == HttpStatus.CREATED) {
            runtime.subtaskIds.add(response.path("id"))
        }
    }

    private fun executePatchSubtask(runtime: ScxmlRuntimeState) {
        val todoId = requireNotNull(runtime.todoId)
        val subtaskId = runtime.subtaskIds.first()
        given()
            .spec(authorizedSpec)
            .body(objectMapper.writeValueAsString(mapOf("completed" to true)))
            .`when`()
            .patch("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HttpStatus.OK.value())
    }

    private fun executeDeleteSubtask(runtime: ScxmlRuntimeState) {
        val todoId = requireNotNull(runtime.todoId)
        val subtaskId = runtime.subtaskIds.removeAt(0)
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$todoId/subtasks/$subtaskId")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())
    }

    private fun assertInvariants(runtime: ScxmlRuntimeState) {
        val activeIds = given()
            .spec(authorizedSpec)
            .queryParam("completed", false)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .extract()
            .jsonPath()
            .getList<String>("id")
        assertEquals(runtime.activeCount, activeIds.size)
    }

    private fun sampleCreateTodoPayload(): Map<String, Any?> {
        val schema = requireNotNull(openAPI.components?.schemas?.get("CreateTodoRequest"))
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
        const val DEFAULT_MBT_ITERATIONS = 15
        const val MIN_STEPS = 5
        const val MAX_STEPS = 12
        const val MAX_CHOICE_INDEX = 100
        const val INITIAL_MAX_ACTIVE_TODOS = 2
        const val ALT_MAX_ACTIVE_TODOS = 1
        const val DEFAULT_ESTIMATED_MINUTES = 30
        const val OPEN_API_SPEC_PATH = "docs/dist/openapi.admin.yaml"
        const val SCXML_MODEL_PATH = "docs/models/todo-lifecycle.scxml"

        val mbtIterations: Int =
            System.getenv("MBT_ITERATIONS")?.toIntOrNull() ?: DEFAULT_MBT_ITERATIONS

        val scxmlModel: ScxmlModel = ScxmlModelLoader.load(SCXML_MODEL_PATH)

        val openAPI = requireNotNull(
            OpenAPIV3Parser().read(
                OPEN_API_SPEC_PATH,
                null,
                ParseOptions().apply {
                    isResolve = true
                    isResolveFully = true
                },
            ),
        ) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val openApiValidationFilter = OpenApiValidationFilter(openAPI)

        private val wireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply {
            start()
            stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/emails"))
                    .willReturn(
                        WireMock.aResponse()
                            .withStatus(HttpStatus.ACCEPTED.value())
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"id":"msg-scxml-mbt","status":"QUEUED"}"""),
                    ),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.email.base-url") { wireMockServer.baseUrl() }
            registry.add("notification.email.api-key") { "test-api-key" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }
}
