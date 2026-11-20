package com.example.todo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.lifecycle.AfterContainer
import net.jqwik.api.lifecycle.BeforeContainer
import net.jqwik.api.stateful.Action
import net.jqwik.api.stateful.ActionSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Pure black-box jqwik property-based & stateful integration tests against the HTTP API.
 * Uses SERVER_URL when targeting an external container stack, or starts an embedded app once per container.
 */
@Suppress("TooManyFunctions")
class OpenApiJqwikPropertyIntegrationTest {
    @Property(tries = TRIES)
    fun validCreateTodoRequestMatchesOpenApiSpec(
        @ForAll("validCreateTodoPayloads") payload: Map<String, Any?>,
    ) {
        val createdId = createTodo(payload)
        val fetched = given()
            .baseUri(baseUrl)
            .header("Authorization", "Bearer $authToken")
            .filter(openApiValidationFilter)
            .`when`()
            .get("/api/todos/$createdId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .extract()
            .jsonPath()

        assertEquals(payload["title"].toString().trim(), fetched.getString("title"))
        deleteTodo(createdId)
    }

    @Property(tries = TRIES)
    fun invalidCreateTodoRequestReturnsBadRequest(
        @ForAll("invalidCreateTodoPayloads") invalidPayload: Map<String, Any?>,
    ) {
        given()
            .baseUri(baseUrl)
            .header("Authorization", "Bearer $authToken")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .filter(openApiValidationFilter)
            .contentType(ContentType.JSON)
            .body(objectMapper.writeValueAsString(invalidPayload))
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Property(tries = STATEFUL_TRIES)
    fun todoCrudStateMachineFollowsOpenApiSpec(
        @ForAll("todoActionSequences") sequence: ActionSequence<TodoApiModel>,
    ) {
        val finalModel = sequence.run(TodoApiModel())
        finalModel.activeTodoIds.toList().forEach(::deleteTodo)
    }

    @Provide
    fun validCreateTodoPayloads(): Arbitrary<Map<String, Any?>> =
        OpenApiJqwikArbitrary.validObject(requireSchema("CreateTodoRequest"), todoOverrides)

    @Provide
    fun invalidCreateTodoPayloads(): Arbitrary<Map<String, Any?>> =
        OpenApiJqwikArbitrary.invalidObject(requireSchema("CreateTodoRequest"), todoOverrides)

    @Provide
    fun todoActionSequences(): Arbitrary<ActionSequence<TodoApiModel>> {
        val createSchema = requireSchema("CreateTodoRequest")
        val createAction = OpenApiJqwikArbitrary.validObject(createSchema, todoOverrides)
            .map<Action<TodoApiModel>> { payload -> CreateTodoAction(payload) }
        val getAction = Arbitraries.just<Action<TodoApiModel>>(GetRandomTodoAction())
        val deleteAction = Arbitraries.just<Action<TodoApiModel>>(DeleteRandomTodoAction())
        return Arbitraries.sequences(Arbitraries.oneOf(createAction, getAction, deleteAction))
            .ofSize(MAX_SEQUENCE_STEPS)
    }

    class TodoApiModel {
        val activeTodoIds: MutableList<String> = mutableListOf()
        val titlesById: MutableMap<String, String> = mutableMapOf()
    }

    private class CreateTodoAction(
        private val payload: Map<String, Any?>,
    ) : Action<TodoApiModel> {
        override fun precondition(model: TodoApiModel): Boolean =
            model.activeTodoIds.size < MAX_TRACKED_TODOS

        override fun run(model: TodoApiModel): TodoApiModel {
            val id = createTodo(payload)
            model.activeTodoIds.add(id)
            model.titlesById[id] = payload["title"].toString().trim()
            return model
        }
    }

    private class GetRandomTodoAction : Action<TodoApiModel> {
        override fun precondition(model: TodoApiModel): Boolean =
            model.activeTodoIds.isNotEmpty()

        override fun run(model: TodoApiModel): TodoApiModel {
            val id = model.activeTodoIds.last()
            val title = given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer $authToken")
                .filter(openApiValidationFilter)
                .`when`()
                .get("/api/todos/$id")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<String>("title")
            assertEquals(model.titlesById[id], title)
            return model
        }
    }

    private class DeleteRandomTodoAction : Action<TodoApiModel> {
        override fun precondition(model: TodoApiModel): Boolean =
            model.activeTodoIds.isNotEmpty()

        override fun run(model: TodoApiModel): TodoApiModel {
            val id = model.activeTodoIds.removeAt(model.activeTodoIds.lastIndex)
            model.titlesById.remove(id)
            deleteTodo(id)
            return model
        }
    }

    companion object {
        private const val TRIES = 15
        private const val STATEFUL_TRIES = 5
        private const val MAX_SEQUENCE_STEPS = 6
        private const val MAX_TRACKED_TODOS = 10
        private const val USERNAME_SUFFIX_LENGTH = 8
        private const val OPEN_API_SPEC_PATH = "docs/dist/openapi.admin.yaml"

        private var appContext: ConfigurableApplicationContext? = null
        private var wireMockServer: WireMockServer? = null
        private lateinit var baseUrl: String
        private lateinit var authToken: String

        private val objectMapper = ObjectMapper()

        private val openAPI = requireNotNull(
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

        private val openApiValidationFilter = OpenApiValidationFilter(openAPI)

        private val todoOverrides = mapOf<String, Arbitrary<Any?>>(
            "projectId" to Arbitraries.just(null),
            "assigneeId" to Arbitraries.just(null),
        )

        @JvmStatic
        @BeforeContainer
        fun startTargetAndRegisterUser() {
            val externalUrl = System.getenv("SERVER_URL")
            if (!externalUrl.isNullOrBlank()) {
                baseUrl = externalUrl.trimEnd('/')
            } else {
                val wm = WireMockServer(wireMockConfig().dynamicPort()).apply {
                    start()
                    stubFor(
                        WireMock.post(WireMock.urlEqualTo("/api/v1/emails"))
                            .willReturn(
                                WireMock.aResponse()
                                    .withStatus(HttpStatus.ACCEPTED.value())
                                    .withHeader("Content-Type", "application/json")
                                    .withBody("""{"id":"msg-jqwik","status":"QUEUED"}"""),
                            ),
                    )
                }
                wireMockServer = wm
                val ctx = SpringApplicationBuilder(TodoApplication::class.java)
                    .properties(
                        "server.port=0",
                        "notification.email.base-url=${wm.baseUrl()}",
                        "notification.email.api-key=test-api-key",
                    )
                    .run()
                appContext = ctx
                val localPort = ctx.environment.getProperty("local.server.port")
                baseUrl = "http://localhost:$localPort"
            }

            val username = "jqwik_${UUID.randomUUID().toString().take(USERNAME_SUFFIX_LENGTH)}"
            val credentials = mapOf("username" to username, "password" to "Password123!")
            given()
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(credentials))
                .`when`()
                .post("/api/auth/register")
                .then()
                .statusCode(HttpStatus.CREATED.value())

            authToken = given()
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(credentials))
                .`when`()
                .post("/api/auth/login")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path("token")
        }

        @JvmStatic
        @AfterContainer
        fun stopTarget() {
            appContext?.close()
            wireMockServer?.stop()
        }

        private fun requireSchema(name: String) =
            requireNotNull(openAPI.components?.schemas?.get(name)) {
                "Schema '$name' not found in $OPEN_API_SPEC_PATH"
            }

        private fun createTodo(payload: Map<String, Any?>): String =
            given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer $authToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .filter(openApiValidationFilter)
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(payload))
                .`when`()
                .post("/api/todos")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("id")

        private fun deleteTodo(todoId: String) {
            given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer $authToken")
                .filter(openApiValidationFilter)
                .`when`()
                .delete("/api/todos/$todoId")
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value())
        }
    }
}
