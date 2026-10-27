package com.example.todo

import com.example.todo.domain.event.TodoEventType
import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.User
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.infrastructure.persistence.SpringDataUserSettingsRepository
import com.example.todo.infrastructure.pubsub.PubSubProperties
import com.example.todo.infrastructure.security.JwtTokenProvider
import tools.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Suppress("TooManyFunctions", "LargeClass")
class TodoApiIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val pubSubReceiver: PubSubTestReceiver,
    private val jwtTokenProvider: JwtTokenProvider,
    private val springDataUserSettingsRepository: SpringDataUserSettingsRepository,
) {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var defaultUserId: UUID
    private lateinit var defaultToken: String

    @BeforeEach
    fun cleanDatabase() {
        todoRepository.findAll().forEach(todoRepository::delete)
        userRepository.deleteAll()
        springDataUserSettingsRepository.deleteAll()
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }
        val user = userRepository.save(User(username = "default_user", passwordHash = "hash"))
        defaultUserId = requireNotNull(user.id)
        defaultToken = jwtTokenProvider.generateToken(AuthenticatedUser(defaultUserId, user.username))
    }

    @Suppress("LongParameterList")
    private fun testTodo(
        id: UUID? = null,
        userId: UUID = defaultUserId,
        title: String,
        completed: Boolean = false,
        description: String? = null,
        dueDate: LocalDate? = null,
        priority: TodoPriority = TodoPriority.MEDIUM,
        estimatedMinutes: Int? = null,
    ) = Todo(
        id = id,
        userId = userId,
        title = title,
        completed = completed,
        description = description,
        dueDate = dueDate,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
    )

    @Test
    fun registerUserReturnsCreatedUser() {
        val response = validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"newuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("username", equalTo("newuser"))
            .extract()

        val userId = UUID.fromString(response.path("id"))
        val stored = userRepository.findById(userId)
        assertTrue(stored != null)
    }

    @Test
    fun registerUserDuplicateUsernameReturnsConflict() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"dupuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"dupuser","password":"password456"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HttpStatus.CONFLICT.value())
    }

    @Test
    fun loginUserReturnsAuthTokenForValidCredentials() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"loginuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"loginuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("tokenType", equalTo("Bearer"))
            .body("expiresIn", equalTo(DEFAULT_TOKEN_EXPIRES_IN))
    }

    @Test
    fun loginUserInvalidCredentialsReturnsUnauthorized() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"wronguser","password":"password123"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun unauthenticatedTodoRequestsReturnUnauthorized() {
        val missingId = UUID.randomUUID()
        validatedRequest(token = null)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedPostRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .`when`()
            .get("/api/todos/$missingId")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$missingId")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"completed":true}""")
            .`when`()
            .patch("/api/todos/$missingId")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .`when`()
            .delete("/api/todos/$missingId")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun unauthenticatedSettingsAndAdminRequestsReturnUnauthorized() {
        validatedRequest(token = null)
            .`when`()
            .get("/api/user/settings")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"HIGH"}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .`when`()
            .get("/api/admin/stats")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = null)
            .`when`()
            .get("/api/admin/users")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun userCannotAccessAnotherUsersTodo() {
        val createResponse = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"User A secret"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
        val todoId = UUID.fromString(createResponse.path<String>("id"))

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"userb","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        val loginResponse = validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"userb","password":"password123"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HttpStatus.OK.value())
            .extract()
        val userBToken = loginResponse.path<String>("token")

        validatedRequest(token = userBToken)
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("find { it.id == '$todoId' }", equalTo(null))

        validatedRequest(token = userBToken)
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        validatedRequest(token = userBToken)
            .contentType(ContentType.JSON)
            .body("""{"title":"Hacked","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        validatedRequest(token = userBToken)
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun createTodoReturnsCreatedTodo() {
        val response = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .extract()

        val createdId = UUID.fromString(response.path("id"))
        val expected = testTodo(
            title = "Buy milk",
            completed = false,
            description = null,
            dueDate = null,
            priority = TodoPriority.MEDIUM,
            estimatedMinutes = null,
        )
        assertStoredTodo(createdId, expected)
        assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
    }

    @Test
    fun createTodoReplaysOriginalResponseForSameIdempotencyKey() {
        val idempotencyKey = UUID.randomUUID().toString()
        val initialTodoCount = todoRepository.findAll().size

        val originalId = validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path<String>("id")

        val todoId = UUID.fromString(originalId)
        val initialExpected = testTodo(title = "Buy milk", completed = false, priority = TodoPriority.MEDIUM)
        assertStoredTodo(todoId, initialExpected)

        if (pubSubReceiver.isAvailable) {
            val initialEvents = pubSubReceiver.pullEvents(maxMessages = 1)
            assertEquals(1, initialEvents.size, "Expected 1 CREATED event for initial create")
            assertEventTodo(initialExpected, initialEvents.first().event.todo)
            pubSubReceiver.drain()
        }

        val createdTodo = requireNotNull(todoRepository.findById(todoId))
        val modifiedTodo = createdTodo.copy(title = "Changed after creation")
        todoRepository.save(modifiedTodo)

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("id", equalTo(originalId))
            .body("title", equalTo("Buy milk"))

        assertEquals(initialTodoCount + 1, todoRepository.findAll().size)
        assertStoredTodo(todoId, modifiedTodo)

        if (pubSubReceiver.isAvailable) {
            val replayedEvents = pubSubReceiver.pullEvents(
                maxMessages = 1,
                maxWait = Duration.ofMillis(SHORT_WAIT_TIMEOUT_MS),
            )
            assertEquals(0, replayedEvents.size, "Replaying idempotent request must not publish duplicate events")
        }
    }

    @Test
    fun createTodoReturnsConflictWhenIdempotencyKeyIsReusedForDifferentRequest() {
        val idempotencyKey = UUID.randomUUID().toString()
        val initialTodoCount = todoRepository.findAll().size

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy bread"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CONFLICT.value())
            .body("message", equalTo("Idempotency key '$idempotencyKey' was already used with a different request"))

        assertEquals(initialTodoCount + 1, todoRepository.findAll().size)
    }

    @Test
    fun createTodoWithMetadataReturnsCreatedTodo() {
        val response = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title":"  Buy milk  ",
                  "description":"Buy before the weekend",
                  "dueDate":"2025-04-18",
                  "priority":"HIGH",
                  "estimatedMinutes":30
                }
                """.trimIndent(),
            )
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
            .extract()

        val createdId = UUID.fromString(response.path("id"))
        val expected = testTodo(
            title = "Buy milk",
            completed = false,
            description = "Buy before the weekend",
            dueDate = LocalDate.parse("2025-04-18"),
            priority = TodoPriority.HIGH,
            estimatedMinutes = ESTIMATED_MINUTES,
        )
        assertStoredTodo(createdId, expected)
        assertPublishedEvent(TodoEventType.CREATED, createdId, expected)
    }

    @Test
    fun createAndUpdateTodoAcceptEstimatedMinutesMinimum() {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","estimatedMinutes":0}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
            .extract()
            .path<String>("id")

        val id = UUID.fromString(todoId)
        val expectedCreate = testTodo(title = "Buy milk", completed = false, estimatedMinutes = MIN_ESTIMATED_MINUTES)
        assertStoredTodo(id, expectedCreate)
        assertPublishedEvent(TodoEventType.CREATED, id, expectedCreate)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":0}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))

        val expectedUpdate = testTodo(title = "Buy milk", completed = true, estimatedMinutes = MIN_ESTIMATED_MINUTES)
        assertStoredTodo(id, expectedUpdate)
        assertPublishedEvent(TodoEventType.UPDATED, id, expectedUpdate)
    }

    @ParameterizedTest(name = "Priority: {0}")
    @EnumSource(TodoPriority::class)
    fun createAndUpdateTodoAcceptEveryPriorityValue(priority: TodoPriority) {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","priority":"${priority.name}"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("priority", equalTo(priority.name))
            .extract()
            .path<String>("id")

        val id = UUID.fromString(todoId)
        val expectedCreate = testTodo(title = "Buy milk", completed = false, priority = priority)
        assertStoredTodo(id, expectedCreate)
        assertPublishedEvent(TodoEventType.CREATED, id, expectedCreate)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true,"priority":"${priority.name}"}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("priority", equalTo(priority.name))

        val expectedUpdate = testTodo(title = "Milk bought", completed = true, priority = priority)
        assertStoredTodo(id, expectedUpdate)
        assertPublishedEvent(TodoEventType.UPDATED, id, expectedUpdate)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#validationErrorTestCases")
    fun validationErrorsReturnBadRequest(tc: ValidationErrorTestCase) {
        val targetId = if (tc.needsExistingTodo) {
            val todo = todoRepository.save(testTodo(title = "Buy milk"))
            requireNotNull(todo.id)
        } else {
            null
        }
        val expectedCount = if (tc.needsExistingTodo) 1 else 0

        val request = when {
            tc.operationId == "createTodo" -> validatedPostRequest()
            tc.operationId in listOf("registerUser", "loginUser") -> validatedRequest(token = null)
            else -> validatedRequest()
        }

        request
            .contentType(ContentType.JSON)
            .body(tc.requestBody)
            .`when`()
            .request(tc.method.name, tc.pathProvider(targetId))
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        assertEquals(expectedCount, todoRepository.findAll().size)
    }

    @Test
    fun listTodosReturnsEmptyListWhenNoTodosExist() {
        validatedRequest()
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("$", hasSize<Any>(0))
    }

    @Test
    fun listTodosReturnsStoredTodos() {
        val todo = todoRepository.save(testTodo(title = "Buy milk"))

        validatedRequest()
            .`when`()
            .get("/api/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", hasItem(requireNotNull(todo.id).toString()))
    }

    @Test
    fun getTodoReturnsMatchingTodo() {
        val dueDate = LocalDate.parse("2025-04-18")
        val todo = todoRepository.save(
            testTodo(
                title = "Buy milk",
                completed = true,
                description = "Buy before the weekend",
                dueDate = dueDate,
                priority = TodoPriority.HIGH,
                estimatedMinutes = 30,
            ),
        )
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .`when`()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("id", equalTo(todoId.toString()))
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#notFoundTestCases")
    fun missingTodoReturnsNotFound(tc: NotFoundTestCase) {
        val request = validatedRequest()
        if (tc.requestBody != null) {
            request.contentType(ContentType.JSON).body(tc.requestBody)
        }

        request
            .`when`()
            .request(tc.method.name, "/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        assertEquals(0, todoRepository.findAll().size)
    }

    @Test
    fun verifyOpenApiSpecAlignment() {
        val missing = coverageDefinition.operationResponses - TodoApiTestCases.acceptanceCriteriaMap.keys
        assertTrue(
            missing.isEmpty(),
            "The following OAS operation responses do not have corresponding acceptance criteria in tests: " +
                missing.joinToString { "${it.first} -> ${it.second}" },
        )
    }

    @Test
    fun updateTodoChangesStoredTodo() {
        val todo = todoRepository.save(testTodo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title":"Milk bought",
                  "completed":true,
                  "description":"Purchased",
                  "dueDate":"2025-04-19",
                  "priority":"LOW",
                  "estimatedMinutes":45
                }
                """.trimIndent(),
            )
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Purchased"))
            .body("dueDate", equalTo("2025-04-19"))
            .body("priority", equalTo("LOW"))
            .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))

        val expectedFirst = testTodo(
            title = "Milk bought",
            completed = true,
            description = "Purchased",
            dueDate = LocalDate.parse("2025-04-19"),
            priority = TodoPriority.LOW,
            estimatedMinutes = UPDATED_ESTIMATED_MINUTES,
        )
        assertStoredTodo(todoId, expectedFirst)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expectedFirst)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .body("estimatedMinutes", equalTo(null))

        val expectedSecond = testTodo(title = "Milk bought", completed = true, priority = TodoPriority.MEDIUM)
        assertStoredTodo(todoId, expectedSecond)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expectedSecond)
    }

    @Test
    fun listTodosFiltersByCompletedAndPriority() {
        todoRepository.save(testTodo(title = "Task 1", completed = false, priority = TodoPriority.LOW))
        todoRepository.save(testTodo(title = "Task 2", completed = true, priority = TodoPriority.HIGH))
        todoRepository.save(testTodo(title = "Task 3", completed = false, priority = TodoPriority.HIGH))

        validatedRequest()
            .`when`()
            .get("/api/todos?completed=true&priority=HIGH")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("$", hasSize<Any>(1))
            .body("[0].title", equalTo("Task 2"))

        validatedRequest()
            .`when`()
            .get("/api/todos?completed=false")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("$", hasSize<Any>(2))

        validatedRequest()
            .`when`()
            .get("/api/todos?priority=HIGH")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("$", hasSize<Any>(2))
    }

    @Test
    fun patchTodoChangesStoredTodo() {
        val todo = todoRepository.save(testTodo(title = "Buy milk", completed = false, priority = TodoPriority.LOW))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"completed":true,"priority":"HIGH"}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
            .body("priority", equalTo("HIGH"))

        val expected = testTodo(title = "Buy milk", completed = true, priority = TodoPriority.HIGH)
        assertStoredTodo(todoId, expected)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
    }

    @ParameterizedTest(name = "Priority: {0}")
    @EnumSource(TodoPriority::class)
    fun patchTodoAcceptsEveryPriorityValue(priority: TodoPriority) {
        val todo = todoRepository.save(testTodo(title = "Buy milk", priority = TodoPriority.LOW))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"priority":"${priority.name}"}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("priority", equalTo(priority.name))

        val expected = testTodo(title = "Buy milk", completed = false, priority = priority)
        assertStoredTodo(todoId, expected)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
    }

    @Test
    fun patchTodoAcceptsEstimatedMinutesMinimum() {
        val todo = todoRepository.save(testTodo(title = "Buy milk", estimatedMinutes = 30))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"estimatedMinutes":0}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))

        val expected = testTodo(title = "Buy milk", completed = false, estimatedMinutes = MIN_ESTIMATED_MINUTES)
        assertStoredTodo(todoId, expected)
        assertPublishedEvent(TodoEventType.UPDATED, todoId, expected)
    }

    @Test
    fun deleteTodoRemovesStoredTodo() {
        val todo = todoRepository.save(testTodo(title = "Buy milk"))
        val todoId = requireNotNull(todo.id)

        validatedRequest()
            .`when`()
            .delete("/api/todos/$todoId")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        assertEquals(null, todoRepository.findById(todoId))

        assertPublishedEvent(TodoEventType.DELETED, todoId) {
            assertEquals(null, it.event.todo)
        }
    }

    @Test
    fun getUserSettingsReturnsDefaultsWhenUnconfigured() {
        validatedRequest()
            .`when`()
            .get("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("userId", equalTo(defaultUserId.toString()))
            .body("defaultPriority", equalTo("MEDIUM"))
            .body("defaultEstimatedMinutes", equalTo(null))
            .body("maxActiveTodos", equalTo(null))
    }

    @ParameterizedTest
    @EnumSource(TodoPriority::class)
    fun updateUserSettingsAcceptsEveryPriorityValue(priority: TodoPriority) {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"${priority.name}"}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("defaultPriority", equalTo(priority.name))
    }

    @Test
    fun updateUserSettingsAcceptsBoundaryValues() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"MEDIUM","defaultEstimatedMinutes":0,"maxActiveTodos":1}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("defaultEstimatedMinutes", equalTo(0))
            .body("maxActiveTodos", equalTo(1))
    }

    @Test
    fun userSettingsAffectTodoCreationDefaults() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":45}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())

        val createResponse = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Task with user settings"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))
            .extract()

        val todoId = UUID.fromString(createResponse.path<String>("id"))
        val stored = todoRepository.findById(todoId)
        assertTrue(stored != null)
        assertEquals(TodoPriority.HIGH, stored?.priority)
        assertEquals(UPDATED_ESTIMATED_MINUTES, stored?.estimatedMinutes)
    }

    @Test
    fun maxActiveTodosLimitEnforcedOnCreateAndUpdate() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"MEDIUM","maxActiveTodos":2}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HttpStatus.OK.value())

        val firstId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 1"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract().path<String>("id")

        validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 2"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        // 3rd active todo must be rejected with 400
        validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 3 - exceeds limit"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        // Mark 1st todo completed
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 1","completed":true}""")
            .`when`()
            .put("/api/todos/$firstId")
            .then()
            .statusCode(HttpStatus.OK.value())

        // Now creating another active todo succeeds
        validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 3 - now succeeds"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        // Reactivating 1st todo exceeds limit of 2, so it must be rejected with 400
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Active task 1","completed":false}""")
            .`when`()
            .put("/api/todos/$firstId")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun adminEndpointsRejectInvalidApiKey() {
        validatedRequest(token = defaultToken)
            .`when`()
            .get("/api/admin/stats")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedRequest(token = defaultToken)
            .`when`()
            .get("/api/admin/users")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        validatedAdminRequest(adminKey = INVALID_ADMIN_KEY)
            .`when`()
            .get("/api/admin/stats")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())

        validatedAdminRequest(adminKey = INVALID_ADMIN_KEY)
            .`when`()
            .get("/api/admin/users")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
    }

    @Test
    fun getAdminStatsReturnsAggregateMetrics() {
        // Create an uncompleted todo and a completed todo
        todoRepository.save(
            Todo(
                userId = defaultUserId,
                title = "Uncompleted task",
                completed = false,
            ),
        )
        todoRepository.save(
            Todo(
                userId = defaultUserId,
                title = "Completed task",
                completed = true,
            ),
        )

        validatedAdminRequest()
            .`when`()
            .get("/api/admin/stats")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("totalUsers", equalTo(1))
            .body("totalTodos", equalTo(2))
            .body("completedTodos", equalTo(1))
            .body("activeTodos", equalTo(1))
    }

    @Test
    fun getAdminUsersReturnsRegisteredUsers() {
        validatedAdminRequest()
            .`when`()
            .get("/api/admin/users")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("$", hasSize<Any>(1))
            .body("[0].id", equalTo(defaultUserId.toString()))
            .body("[0].username", equalTo("default_user"))
    }

    private fun assertStoredTodo(id: UUID, expected: Todo) {
        val stored = requireNotNull(todoRepository.findById(id)) {
            "Expected todo with ID $id to exist in database, but was not found"
        }
        assertEquals(expected.userId, stored.userId)
        assertEquals(expected.title, stored.title)
        assertEquals(expected.completed, stored.completed)
        assertEquals(expected.description, stored.description)
        assertEquals(expected.dueDate, stored.dueDate)
        assertEquals(expected.priority, stored.priority)
        assertEquals(expected.estimatedMinutes, stored.estimatedMinutes)
    }

    private fun assertEventTodo(expected: Todo, actual: Todo?) {
        val todo = requireNotNull(actual) { "Expected todo event payload, but was null" }
        assertEquals(expected.userId, todo.userId)
        assertEquals(expected.title, todo.title)
        assertEquals(expected.completed, todo.completed)
        assertEquals(expected.description, todo.description)
        assertEquals(expected.dueDate, todo.dueDate)
        assertEquals(expected.priority, todo.priority)
        assertEquals(expected.estimatedMinutes, todo.estimatedMinutes)
    }

    private fun assertPublishedEvent(
        expectedType: TodoEventType,
        expectedTodoId: UUID,
        expectedTodo: Todo? = null,
        assertDetails: ((ReceivedTodoEvent) -> Unit)? = null,
    ) {
        if (!pubSubReceiver.isAvailable) return
        val events = pubSubReceiver.pullEvents(maxMessages = 1)
        assertEquals(1, events.size, "Expected 1 ${expectedType.name} event to be published")
        val received = events.first()
        assertEquals(expectedType.name, received.attributes["eventType"])
        assertEquals(expectedTodoId.toString(), received.attributes["todoId"])
        assertEquals(expectedType, received.event.eventType)
        assertEquals(expectedTodoId, received.event.todoId)
        if (expectedTodo != null) {
            assertEventTodo(expectedTodo, received.event.todo)
        }
        assertDetails?.invoke(received)
    }

    @TestConfiguration
    class PubSubTestConfiguration {
        @Bean(destroyMethod = "close")
        fun pubSubTestReceiver(
            pubSubProperties: PubSubProperties,
            objectMapper: ObjectMapper,
        ): PubSubTestReceiver =
            PubSubTestReceiver(
                projectId = pubSubProperties.projectId,
                topicId = pubSubProperties.topicId,
                emulatorHost = pubSubProperties.emulatorHost,
                objectMapper = objectMapper,
            )
    }

    private fun validatedRequest(
        token: String? = defaultToken,
        adminKey: String? = null,
    ) = given()
        .port(port)
        .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
        .let { if (adminKey != null) it.header("X-Admin-Key", adminKey) else it }
        .filter(openApiCoverageFilter)
        .filter(openApiValidationFilter)

    private fun validatedAdminRequest(adminKey: String? = VALID_ADMIN_KEY) =
        validatedRequest(token = null, adminKey = adminKey)

    private fun validatedPostRequest(
        idempotencyKey: String = UUID.randomUUID().toString(),
        token: String? = defaultToken,
    ) = validatedRequest(token)
        .header("Idempotency-Key", idempotencyKey)

    private companion object {
        const val VALID_ADMIN_KEY = "admin-secret-key"
        const val INVALID_ADMIN_KEY = "wrong-admin-key"
        const val DEFAULT_TOKEN_EXPIRES_IN = 86400
        const val SHORT_WAIT_TIMEOUT_MS = 500L
        const val ESTIMATED_MINUTES = 30
        const val UPDATED_ESTIMATED_MINUTES = 45
        const val MIN_ESTIMATED_MINUTES = 0
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val OPEN_API_SPEC_PATH = "docs/dist/openapi.admin.yaml"

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

        val coverageDefinition = OpenApiCoverageDefinition.from(openAPI)
        val openApiCoverageFilter = OpenApiCoverageFilter(coverageDefinition)

        val openApiValidationFilter = OpenApiValidationFilter(openAPI)

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiResponsesCovered() {
            val missingResponses = coverageDefinition.responses - openApiCoverageFilter.coveredResponses
            assertTrue(
                missingResponses.isEmpty(),
                "The following OpenAPI responses were not exercised in integration tests: " +
                    missingResponses.joinToString { it.toString() },
            )
        }

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiConstraintsCovered() {
            val missingConstraints = coverageDefinition.constraints - openApiCoverageFilter.coveredConstraints
            assertTrue(
                missingConstraints.isEmpty(),
                "The following OpenAPI validation constraints were not exercised in integration tests: " +
                    missingConstraints.joinToString { it.toString() },
            )
        }
    }
}
