package com.example.todo.mbt

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
import io.restassured.RestAssured.given
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.io.File
import java.util.UUID

/**
 * Model-based integration test that replays Informal Trace Format (ITF) traces generated
 * from the Quint formal specification (`docs/models/todo.qnt`). Every HTTP request passes
 * through [OpenApiValidationFilter] to verify OpenAPI contract compliance on every step.
 */
@Tag("property")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["identity.service.enabled=false"],
)
@Suppress("TooManyFunctions")
class QuintTodoModelBasedIntegrationTest @Autowired constructor(
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
    fun quintItfTracesReplayAgainstApiAndSatisfyOpenApiContract() {
        val traceDir = File(ITF_TRACES_DIR)
        val traceFiles = traceDir.listFiles { file -> file.name.endsWith(".itf.json") }
            ?.sortedBy { it.name }
            .orEmpty()
        require(traceFiles.isNotEmpty()) { "No ITF trace files found in $ITF_TRACES_DIR" }

        for (traceFile in traceFiles) {
            val trace = QuintItfTraceLoader.load(traceFile.path, objectMapper)
            replayTrace(trace)
        }
    }

    private fun replayTrace(trace: QuintItfTrace) {
        resetEnvironment()
        val initial = trace.steps.first()
        configureMaxActiveTodos(initial.maxActiveTodos)

        val context = ReplayContext()
        for (step in trace.steps.drop(1)) {
            executeStep(step, context)
            assertStateInvariants(step, context)
        }
    }

    private fun resetEnvironment() {
        todoRepository.findAll().forEach(todoRepository::delete)
        springDataProjectMemberRepository.deleteAll()
        springDataProjectRepository.deleteAll()
        userRepository.deleteAll()
        wireMockServer.resetRequests()

        val user = userRepository.save(User(username = "quint_mbt_${UUID.randomUUID()}", passwordHash = "hash"))
        val userId = requireNotNull(user.id)
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, user.username))
        authorizedSpec = RequestSpecBuilder()
            .setPort(port)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(openApiValidationFilter)
            .build()
    }

    private fun configureMaxActiveTodos(limit: Int) {
        given()
            .spec(authorizedSpec)
            .body(
                objectMapper.writeValueAsString(
                    mapOf(
                        "defaultPriority" to "MEDIUM",
                        "defaultEstimatedMinutes" to DEFAULT_ESTIMATED_MINUTES,
                        "maxActiveTodos" to limit,
                    ),
                ),
            )
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())
    }

    private fun executeStep(step: ItfTraceStep, context: ReplayContext) {
        when (step.lastAction) {
            "createTodo" -> replayCreateTodo(step, context)
            "getTodo" -> replayGetTodo(step, context)
            "listTodos" -> replayListTodos(step)
            "updateTodo" -> replayUpdateTodo(step, context)
            "patchTodo" -> replayPatchTodo(step, context)
            "deleteTodo" -> replayDeleteTodo(step, context)
            "createTodoSubtask" -> replayCreateSubtask(step, context)
            "listTodoSubtasks" -> replayListSubtasks(step, context)
            "deleteTodoSubtask" -> replayDeleteSubtask(step, context)
            "updateUserSettings" -> configureMaxActiveTodos(step.maxActiveTodos)
            else -> error("Unsupported Quint action in ITF trace: ${step.lastAction}")
        }
    }

    private fun replayCreateTodo(step: ItfTraceStep, context: ReplayContext) {
        val body = objectMapper.writeValueAsString(
            mapOf("title" to "Quint Todo ${step.lastFingerprint}"),
        )
        val response = given()
            .spec(authorizedSpec)
            .header("Idempotency-Key", step.lastKey)
            .body(body)
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(step.lastStatus)
            .extract()

        if (step.lastStatus == HttpStatus.CREATED.value()) {
            val realId = response.path<String>("id")
            val existing = context.todoIds[step.lastTargetId]
            if (existing != null) {
                assertEquals(existing, realId)
            } else {
                context.todoIds[step.lastTargetId] = realId
            }
        }
    }

    private fun replayGetTodo(step: ItfTraceStep, context: ReplayContext) {
        val realId = context.resolveTodoId(step.lastTargetId)
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$realId")
            .then()
            .statusCode(step.lastStatus)
            .extract()

        if (step.lastStatus == HttpStatus.OK.value()) {
            assertEquals(step.lastCompleted, response.path<Boolean>("completed"))
        }
    }

    private fun replayListTodos(step: ItfTraceStep) {
        val ids = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(step.lastStatus)
            .extract()
            .jsonPath()
            .getList<String>("id")
        assertEquals(step.todos.size, ids.size)
    }

    private fun replayUpdateTodo(step: ItfTraceStep, context: ReplayContext) {
        val realId = context.resolveTodoId(step.lastTargetId)
        val body = objectMapper.writeValueAsString(
            mapOf("title" to "Updated ${step.lastTargetId}", "completed" to step.lastCompleted),
        )
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .put("/api/todos/$realId")
            .then()
            .statusCode(step.lastStatus)
    }

    private fun replayPatchTodo(step: ItfTraceStep, context: ReplayContext) {
        val realId = context.resolveTodoId(step.lastTargetId)
        val body = objectMapper.writeValueAsString(mapOf("completed" to step.lastCompleted))
        given()
            .spec(authorizedSpec)
            .body(body)
            .`when`()
            .patch("/api/todos/$realId")
            .then()
            .statusCode(step.lastStatus)
    }

    private fun replayDeleteTodo(step: ItfTraceStep, context: ReplayContext) {
        val realId = context.resolveTodoId(step.lastTargetId)
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$realId")
            .then()
            .statusCode(step.lastStatus)
    }

    private fun replayCreateSubtask(step: ItfTraceStep, context: ReplayContext) {
        val realTodoId = context.resolveTodoId(step.lastTargetId)
        val response = given()
            .spec(authorizedSpec)
            .body(objectMapper.writeValueAsString(mapOf("title" to "Subtask ${step.lastSubtaskId}")))
            .`when`()
            .post("/api/todos/$realTodoId/subtasks")
            .then()
            .statusCode(step.lastStatus)
            .extract()

        if (step.lastStatus == HttpStatus.CREATED.value()) {
            context.subtaskIds[step.lastTargetId to step.lastSubtaskId] = response.path("id")
        }
    }

    private fun replayListSubtasks(step: ItfTraceStep, context: ReplayContext) {
        val realTodoId = context.resolveTodoId(step.lastTargetId)
        val response = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos/$realTodoId/subtasks")
            .then()
            .statusCode(step.lastStatus)
            .extract()

        if (step.lastStatus == HttpStatus.OK.value()) {
            val expectedCount = step.todos[step.lastTargetId]?.subtasks?.size ?: 0
            assertEquals(expectedCount, response.jsonPath().getList<String>("id").size)
        }
    }

    private fun replayDeleteSubtask(step: ItfTraceStep, context: ReplayContext) {
        val realTodoId = context.resolveTodoId(step.lastTargetId)
        val realSubId = context.subtaskIds[step.lastTargetId to step.lastSubtaskId] ?: UUID.randomUUID().toString()
        given()
            .spec(authorizedSpec)
            .`when`()
            .delete("/api/todos/$realTodoId/subtasks/$realSubId")
            .then()
            .statusCode(step.lastStatus)
    }

    private fun assertStateInvariants(step: ItfTraceStep, context: ReplayContext) {
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
        assertEquals(step.activeTodoCount(), activeIds.size)

        val expectedIds = step.todos.keys.mapNotNull { context.todoIds[it] }.toSet()
        val actualAllIds = given()
            .spec(authorizedSpec)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .extract()
            .jsonPath()
            .getList<String>("id")
            .toSet()
        assertEquals(expectedIds, actualAllIds)
    }

    private class ReplayContext {
        val todoIds = mutableMapOf<String, String>()
        val subtaskIds = mutableMapOf<Pair<String, String>, String>()

        fun resolveTodoId(symbolicId: String): String =
            todoIds[symbolicId] ?: UUID.randomUUID().toString()
    }

    private companion object {
        const val DEFAULT_ESTIMATED_MINUTES = 30
        const val OPEN_API_SPEC_PATH = "docs/dist/openapi.admin.yaml"
        const val ITF_TRACES_DIR = "docs/models/traces"

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
                            .withBody("""{"id":"msg-quint-mbt","status":"QUEUED"}"""),
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
