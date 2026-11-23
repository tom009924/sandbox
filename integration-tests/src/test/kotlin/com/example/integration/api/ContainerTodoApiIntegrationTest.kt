package com.example.integration.api

import com.example.integration.api.ContainerApiClient.HTTP_BAD_REQUEST
import com.example.integration.api.ContainerApiClient.HTTP_CONFLICT
import com.example.integration.api.ContainerApiClient.HTTP_CREATED
import com.example.integration.api.ContainerApiClient.HTTP_FORBIDDEN
import com.example.integration.api.ContainerApiClient.HTTP_NOT_FOUND
import com.example.integration.api.ContainerApiClient.HTTP_NO_CONTENT
import com.example.integration.api.ContainerApiClient.HTTP_OK
import com.example.integration.api.ContainerApiClient.HTTP_UNAUTHORIZED
import com.example.integration.openapi.OpenApiCoverageDefinition
import com.example.integration.openapi.OpenApiCoverageFilter
import com.example.integration.openapi.OpenApiCoverageReporter
import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import com.example.integration.support.WireMockAdminClient
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * Full OpenAPI 100% coverage integration test suite executed against the real containerized
 * `api-gateway` -> `identity-service` & `todo-service` -> `spanner-emulator` & `wiremock` stack.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ContainerTodoApiIntegrationTest {

    private lateinit var defaultUserId: UUID
    private lateinit var defaultToken: String

    @BeforeEach
    fun cleanDatabase() {
        SpannerDatabaseCleaner.cleanAll()
        val (userId, token) = ContainerApiClient.registerAndLogin("default_user")
        defaultUserId = userId
        defaultToken = token
        WireMockAdminClient.resetRequests()
    }

    @Test
    fun registerUserReturnsCreatedUser() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"newuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)
            .body("username", equalTo("newuser"))

        assertEquals(1, WireMockAdminClient.countPostRequests("/api/v1/emails"))
    }

    @Test
    fun registerUserDuplicateUsernameReturnsConflict() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"dupuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"dupuser","password":"password456"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CONFLICT)
    }

    @Test
    fun loginUserReturnsAuthTokenForValidCredentials() {
        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"loginuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)

        validatedRequest(token = null)
            .contentType(ContentType.JSON)
            .body("""{"username":"loginuser","password":"password123"}""")
            .`when`()
            .post("/api/auth/login")
            .then()
            .statusCode(HTTP_OK)
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
            .statusCode(HTTP_UNAUTHORIZED)
    }

    @Test
    fun unauthenticatedTodoRequestsReturnUnauthorized() {
        val missingId = UUID.randomUUID()
        validatedRequest(token = null).get("/api/todos").then().statusCode(HTTP_UNAUTHORIZED)
        validatedPostRequest(token = null).contentType(ContentType.JSON).body("""{"title":"Buy milk"}""")
            .post("/api/todos").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/todos/$missingId").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .put("/api/todos/$missingId").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON).body("""{"completed":true}""")
            .patch("/api/todos/$missingId").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).delete("/api/todos/$missingId").then().statusCode(HTTP_UNAUTHORIZED)
    }

    @Test
    fun unauthenticatedSettingsAndAdminRequestsReturnUnauthorized() {
        validatedRequest(token = null).get("/api/user/settings").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON).body("""{"defaultPriority":"HIGH"}""")
            .put("/api/user/settings").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/admin/stats").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/admin/users").then().statusCode(HTTP_UNAUTHORIZED)
    }

    @Test
    fun userCannotAccessAnotherUsersTodo() {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"User A secret"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")

        val (_, userBToken) = ContainerApiClient.registerAndLogin("userb")

        validatedRequest(token = userBToken).get("/api/todos")
            .then().statusCode(HTTP_OK).body("find { it.id == '$todoId' }", equalTo(null))
        validatedRequest(token = userBToken).get("/api/todos/$todoId").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = userBToken).contentType(ContentType.JSON)
            .body("""{"title":"Hacked","completed":true}""")
            .put("/api/todos/$todoId").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = userBToken).delete("/api/todos/$todoId").then().statusCode(HTTP_NOT_FOUND)
    }

    @Test
    fun createTodoReturnsCreatedTodo() {
        val createdId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"  Buy milk  "}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(false))
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .extract()
            .path<String>("id")

        validatedRequest().get("/api/todos/$createdId")
            .then().statusCode(HTTP_OK).body("title", equalTo("Buy milk"))
    }

    @Test
    fun createTodoReplaysOriginalResponseForSameIdempotencyKey() {
        val idempotencyKey = UUID.randomUUID().toString()
        val originalId = validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Changed after creation","completed":false}""")
            .`when`()
            .put("/api/todos/$originalId")
            .then()
            .statusCode(HTTP_OK)

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("id", equalTo(originalId))
            .body("title", equalTo("Buy milk"))

        assertEquals(1, ContainerApiClient.listTodoCount(defaultToken))
        validatedRequest().get("/api/todos/$originalId")
            .then().statusCode(HTTP_OK).body("title", equalTo("Changed after creation"))
    }

    @Test
    fun createTodoReturnsConflictWhenIdempotencyKeyIsReusedForDifferentRequest() {
        val idempotencyKey = UUID.randomUUID().toString()
        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)

        validatedPostRequest(idempotencyKey = idempotencyKey)
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy bread"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CONFLICT)

        assertEquals(1, ContainerApiClient.listTodoCount(defaultToken))
    }

    @Test
    fun createTodoWithMetadataReturnsCreatedTodo() {
        validatedPostRequest()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title":"  Buy milk  ",
                  "description":"Buy before the weekend",
                  "dueDate":"2025-04-18",
                  "priority":"HIGH",
                  "estimatedMinutes":30,
                  "tags":["shopping", "groceries"]
                }
                """.trimIndent(),
            )
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
            .body("tags[0]", equalTo("shopping"))
            .body("tags[1]", equalTo("groceries"))
    }

    @Test
    fun createAndUpdateTodoAcceptEstimatedMinutesMinimum() {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","estimatedMinutes":0}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
            .extract()
            .path<String>("id")

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","completed":true,"estimatedMinutes":0}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
    }

    @ParameterizedTest(name = "Priority: {0}")
    @ValueSource(strings = ["LOW", "MEDIUM", "HIGH"])
    fun createAndUpdateTodoAcceptEveryPriorityValue(priority: String) {
        val todoId = validatedPostRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Buy milk","priority":"$priority"}""")
            .`when`()
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("priority", equalTo(priority))
            .extract()
            .path<String>("id")

        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true,"priority":"$priority"}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("priority", equalTo(priority))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.integration.api.TodoApiTestCases#validationErrorTestCases")
    fun validationErrorsReturnBadRequest(tc: ValidationErrorTestCase) {
        val targetId = if (tc.needsExistingTodo) {
            ContainerApiClient.seedTodo(defaultToken, title = "Buy milk")
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
            .statusCode(HTTP_BAD_REQUEST)

        assertEquals(expectedCount, ContainerApiClient.listTodoCount(defaultToken))
    }

    @Test
    fun listTodosReturnsEmptyListWhenNoTodosExist() {
        validatedRequest().get("/api/todos").then().statusCode(HTTP_OK).body("$", hasSize<Any>(0))
    }

    @Test
    fun listTodosReturnsStoredTodos() {
        val todoId = ContainerApiClient.seedTodo(defaultToken, title = "Buy milk")
        validatedRequest().get("/api/todos")
            .then().statusCode(HTTP_OK).body("id", hasItem(todoId.toString()))
    }

    @Test
    fun getTodoReturnsMatchingTodo() {
        val todoId = ContainerApiClient.seedTodo(
            token = defaultToken,
            title = "Buy milk",
            completed = true,
            description = "Buy before the weekend",
            dueDate = "2025-04-18",
            priority = "HIGH",
            estimatedMinutes = ESTIMATED_MINUTES,
        )

        validatedRequest()
            .get("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("id", equalTo(todoId.toString()))
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Buy before the weekend"))
            .body("dueDate", equalTo("2025-04-18"))
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(ESTIMATED_MINUTES))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.integration.api.TodoApiTestCases#notFoundTestCases")
    fun missingTodoReturnsNotFound(tc: NotFoundTestCase) {
        val request = validatedRequest()
        if (tc.requestBody != null) {
            request.contentType(ContentType.JSON).body(tc.requestBody)
        }
        request
            .`when`()
            .request(tc.method.name, "/api/todos/$MISSING_TODO_ID")
            .then()
            .statusCode(HTTP_NOT_FOUND)
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
        val todoId = ContainerApiClient.seedTodo(defaultToken, title = "Buy milk")
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
                  "estimatedMinutes":45,
                  "tags":["updated", "urgent"]
                }
                """.trimIndent(),
            )
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("title", equalTo("Milk bought"))
            .body("completed", equalTo(true))
            .body("description", equalTo("Purchased"))
            .body("dueDate", equalTo("2025-04-19"))
            .body("priority", equalTo("LOW"))
            .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))
            .body("tags[0]", equalTo("updated"))
            .body("tags[1]", equalTo("urgent"))
    }

    @Test
    fun updateTodoClearsOptionalMetadataWhenOmitted() {
        val todoId = ContainerApiClient.seedTodo(
            token = defaultToken,
            title = "Initial Title",
            description = "Initial Description",
            tags = listOf("initial"),
        )
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"title":"Milk bought","completed":true}""")
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("description", equalTo(null))
            .body("dueDate", equalTo(null))
            .body("priority", equalTo("MEDIUM"))
            .body("estimatedMinutes", equalTo(null))
            .body("tags", hasSize<Any>(0))
    }

    @Test
    fun listTodosFiltersByCompletedAndPriority() {
        ContainerApiClient.seedTodo(
            defaultToken,
            title = "Task 1",
            completed = false,
            priority = "LOW",
            tags = listOf("work")
        )
        ContainerApiClient.seedTodo(
            defaultToken,
            title = "Task 2",
            completed = true,
            priority = "HIGH",
            tags = listOf("home")
        )
        ContainerApiClient.seedTodo(
            defaultToken,
            title = "Task 3",
            completed = false,
            priority = "HIGH",
            tags = listOf("work", "urgent")
        )

        validatedRequest().get("/api/todos?completed=true&priority=HIGH")
            .then().statusCode(HTTP_OK).body("$", hasSize<Any>(1)).body("[0].title", equalTo("Task 2"))
        validatedRequest().get("/api/todos?completed=false")
            .then().statusCode(HTTP_OK).body("$", hasSize<Any>(2))
        validatedRequest().get("/api/todos?priority=HIGH")
            .then().statusCode(HTTP_OK).body("$", hasSize<Any>(2))
        validatedRequest().get("/api/todos?tag=urgent")
            .then().statusCode(HTTP_OK).body("$", hasSize<Any>(1)).body("[0].title", equalTo("Task 3"))
    }

    @Test
    @Suppress("MagicNumber")
    fun listTodosFiltersByKeywordAndDueDateRangeAndSortsResults() {
        ContainerApiClient.seedTodo(defaultToken, title = "Buy milk", priority = "LOW", dueDate = "2025-05-10")
        ContainerApiClient.seedTodo(defaultToken, title = "Buy bread", priority = "HIGH", dueDate = "2025-05-05")
        ContainerApiClient.seedTodo(defaultToken, title = "Clean room", priority = "MEDIUM")

        validatedRequest().get("/api/todos?q=buy").then().statusCode(HTTP_OK).body("$", hasSize<Any>(2))
        validatedRequest().get("/api/todos?dueAfter=2025-05-01&dueBefore=2025-05-08")
            .then().statusCode(HTTP_OK).body("$", hasSize<Any>(1)).body("[0].title", equalTo("Buy bread"))
        validatedRequest().get("/api/todos?sortBy=PRIORITY&sortOrder=DESC")
            .then().statusCode(HTTP_OK)
            .body("$", hasSize<Any>(3))
            .body("[0].title", equalTo("Buy bread"))
            .body("[1].title", equalTo("Clean room"))
            .body("[2].title", equalTo("Buy milk"))
    }

    @Test
    fun patchTodoChangesStoredTodo() {
        val todoId = ContainerApiClient.seedTodo(
            defaultToken,
            title = "Buy milk",
            priority = "LOW",
            tags = listOf("original")
        )
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"completed":true,"priority":"HIGH","tags":["patched"]}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("title", equalTo("Buy milk"))
            .body("completed", equalTo(true))
            .body("priority", equalTo("HIGH"))
            .body("tags[0]", equalTo("patched"))
    }

    @ParameterizedTest(name = "Priority: {0}")
    @ValueSource(strings = ["LOW", "MEDIUM", "HIGH"])
    fun patchTodoAcceptsEveryPriorityValue(priority: String) {
        val todoId = ContainerApiClient.seedTodo(defaultToken, title = "Buy milk", priority = "LOW")
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"priority":"$priority"}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("priority", equalTo(priority))
    }

    @Test
    fun patchTodoAcceptsEstimatedMinutesMinimum() {
        val todoId = ContainerApiClient.seedTodo(defaultToken, title = "Buy milk", estimatedMinutes = ESTIMATED_MINUTES)
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"estimatedMinutes":0}""")
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .statusCode(HTTP_OK)
            .body("estimatedMinutes", equalTo(MIN_ESTIMATED_MINUTES))
    }

    @Test
    fun deleteTodoRemovesStoredTodo() {
        val todoId = ContainerApiClient.seedTodo(defaultToken, title = "Buy milk")
        validatedRequest().delete("/api/todos/$todoId").then().statusCode(HTTP_NO_CONTENT)
        validatedRequest().get("/api/todos/$todoId").then().statusCode(HTTP_NOT_FOUND)
    }

    @Test
    fun projectEndpointsRejectUnauthenticatedRequests() {
        val pid = MISSING_TODO_ID
        validatedRequest(token = null).get("/api/projects").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON).body("""{"name":"P"}""")
            .post("/api/projects").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/projects/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/todos/$pid/subtasks").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).post("/api/todos/$pid/subtasks").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).patch("/api/todos/$pid/subtasks/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).delete("/api/todos/$pid/subtasks/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/todos/$pid/comments").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).post("/api/todos/$pid/comments").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).delete("/api/todos/$pid/comments/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON).body("""{"name":"P"}""")
            .put("/api/projects/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).delete("/api/projects/$pid").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).get("/api/projects/$pid/members").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).contentType(ContentType.JSON).body("""{"username":"bob"}""")
            .post("/api/projects/$pid/members").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = null).delete("/api/projects/$pid/members/$pid").then().statusCode(HTTP_UNAUTHORIZED)
    }

    @Test
    fun projectCrudAndMemberAuthorizationFlow() {
        val (memberId, memberToken) = ContainerApiClient.registerAndLogin("project_member")
        val projectId = createProjectAs(defaultToken, "Shared Project")

        validatedRequest().get("/api/projects").then().statusCode(HTTP_OK).body("size()", equalTo(1))
        validatedRequest().get("/api/projects/$projectId").then().statusCode(HTTP_OK)
        validatedRequest(token = memberToken).get("/api/projects/$projectId").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().get("/api/projects/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)

        addAndVerifyProjectMembers(projectId, memberToken, memberId)
        verifyProjectUpdateAndDeleteAuthorization(projectId, memberToken)
    }

    @Test
    fun projectScopedTodoFlow() {
        val (memberId, memberToken) = ContainerApiClient.registerAndLogin("todo_collaborator")
        val projectId = createProjectAs(defaultToken, "Todo Project")
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"username":"todo_collaborator","role":"MEMBER"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_CREATED)

        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Bad project","projectId":"$MISSING_TODO_ID"}""")
            .post("/api/todos").then().statusCode(HTTP_NOT_FOUND)

        val todoId = validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Shared Task","projectId":"$projectId","assigneeId":"$memberId"}""")
            .post("/api/todos").then().statusCode(HTTP_CREATED)
            .body("assigneeId", equalTo(memberId.toString()))
            .extract().path<String>("id")

        validatedRequest(token = memberToken).get("/api/todos?projectId=$projectId&assigneeId=$memberId")
            .then().statusCode(HTTP_OK).body("size()", equalTo(1)).body("[0].id", equalTo(todoId))
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Invalid Assignee","projectId":"$projectId","assigneeId":"$MISSING_TODO_ID"}""")
            .post("/api/todos").then().statusCode(HTTP_BAD_REQUEST)
        validatedRequest(token = memberToken).get("/api/todos?projectId=$projectId")
            .then().statusCode(HTTP_OK).body("size()", equalTo(1)).body("[0].id", equalTo(todoId))

        verifyTodoSubtasksFlow(todoId, memberToken)
        verifyTodoCommentsFlow(todoId, memberToken)

        validatedRequest(token = memberToken)
            .get("/api/todos?projectId=$projectId&completed=false&priority=MEDIUM&tag=work")
            .then().statusCode(HTTP_OK).body("size()", equalTo(0))
        validatedRequest(token = memberToken)
            .get("/api/todos?projectId=$projectId&completed=true&priority=HIGH")
            .then().statusCode(HTTP_OK).body("size()", equalTo(0))
    }

    @Test
    fun getUserSettingsReturnsDefaultsWhenUnconfigured() {
        validatedRequest().get("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
            .body("userId", equalTo(defaultUserId.toString()))
            .body("defaultPriority", equalTo("MEDIUM"))
            .body("defaultEstimatedMinutes", equalTo(null))
            .body("maxActiveTodos", equalTo(null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LOW", "MEDIUM", "HIGH"])
    fun updateUserSettingsAcceptsEveryPriorityValue(priority: String) {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"$priority"}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
            .body("defaultPriority", equalTo(priority))
    }

    @Test
    fun updateUserSettingsAcceptsBoundaryValues() {
        validatedRequest()
            .contentType(ContentType.JSON)
            .body("""{"defaultPriority":"MEDIUM","defaultEstimatedMinutes":0,"maxActiveTodos":1}""")
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
            .body("defaultEstimatedMinutes", equalTo(0))
            .body("maxActiveTodos", equalTo(1))
    }

    @Test
    fun userSettingsAffectTodoCreationDefaults() {
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"defaultPriority":"HIGH","defaultEstimatedMinutes":45}""")
            .put("/api/user/settings").then().statusCode(HTTP_OK)

        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Task with user settings"}""")
            .post("/api/todos")
            .then()
            .statusCode(HTTP_CREATED)
            .body("priority", equalTo("HIGH"))
            .body("estimatedMinutes", equalTo(UPDATED_ESTIMATED_MINUTES))
    }

    @Test
    fun maxActiveTodosLimitEnforcedOnCreateAndUpdate() {
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"defaultPriority":"MEDIUM","maxActiveTodos":2}""")
            .put("/api/user/settings").then().statusCode(HTTP_OK)

        val firstId = validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 1"}""")
            .post("/api/todos").then().statusCode(HTTP_CREATED).extract().path<String>("id")
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 2"}""")
            .post("/api/todos").then().statusCode(HTTP_CREATED)
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 3 - exceeds limit"}""")
            .post("/api/todos").then().statusCode(HTTP_BAD_REQUEST)

        validatedRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 1","completed":true}""")
            .put("/api/todos/$firstId").then().statusCode(HTTP_OK)
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 3 - now succeeds"}""")
            .post("/api/todos").then().statusCode(HTTP_CREATED)
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"title":"Active task 1","completed":false}""")
            .put("/api/todos/$firstId").then().statusCode(HTTP_BAD_REQUEST)
    }

    @Test
    fun adminEndpointsRejectInvalidApiKey() {
        validatedRequest(token = defaultToken).get("/api/admin/stats").then().statusCode(HTTP_UNAUTHORIZED)
        validatedRequest(token = defaultToken).get("/api/admin/users").then().statusCode(HTTP_UNAUTHORIZED)
        validatedAdminRequest(adminKey = INVALID_ADMIN_KEY).get("/api/admin/stats").then().statusCode(HTTP_FORBIDDEN)
        validatedAdminRequest(adminKey = INVALID_ADMIN_KEY).get("/api/admin/users").then().statusCode(HTTP_FORBIDDEN)
    }

    @Test
    fun getAdminStatsReturnsAggregateMetrics() {
        ContainerApiClient.seedTodo(defaultToken, title = "Uncompleted task", completed = false)
        ContainerApiClient.seedTodo(defaultToken, title = "Completed task", completed = true)

        validatedAdminRequest()
            .get("/api/admin/stats")
            .then()
            .statusCode(HTTP_OK)
            .body("totalUsers", equalTo(1))
            .body("totalTodos", equalTo(2))
            .body("completedTodos", equalTo(1))
            .body("activeTodos", equalTo(1))
    }

    @Test
    fun getAdminUsersReturnsRegisteredUsers() {
        validatedAdminRequest()
            .get("/api/admin/users")
            .then()
            .statusCode(HTTP_OK)
            .body("$", hasSize<Any>(1))
            .body("[0].id", equalTo(defaultUserId.toString()))
            .body("[0].username", equalTo("default_user"))
    }

    @Test
    fun patternConstraintsRejectInvalidInputs() {
        val invalidDesc = "\\u0000"
        validatedRequest(token = null).contentType(ContentType.JSON)
            .body("""{"username":" ","password":"Password123!"}""")
            .post("/api/auth/login").then().statusCode(HTTP_BAD_REQUEST)
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"title":"Test","description":"$invalidDesc"}""")
            .post("/api/todos").then().statusCode(HTTP_BAD_REQUEST)
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"title":"Test","completed":false,"description":"$invalidDesc"}""")
            .put("/api/todos/{id}", MISSING_TODO_ID).then().statusCode(HTTP_BAD_REQUEST)
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"description":"$invalidDesc"}""")
            .patch("/api/todos/{id}", MISSING_TODO_ID).then().statusCode(HTTP_BAD_REQUEST)
        validatedPostRequest().contentType(ContentType.JSON)
            .body("""{"name":"Proj","description":"$invalidDesc"}""")
            .post("/api/projects").then().statusCode(HTTP_BAD_REQUEST)
        validatedRequest().contentType(ContentType.JSON)
            .body("""{"name":"Proj","description":"$invalidDesc"}""")
            .put("/api/projects/{projectId}", MISSING_TODO_ID).then().statusCode(HTTP_BAD_REQUEST)
    }

    private fun verifyTodoSubtasksFlow(todoId: String, memberToken: String) {
        val subtaskId = validatedRequest(token = memberToken).contentType(ContentType.JSON)
            .body("""{"title":"Step 1"}""")
            .post("/api/todos/$todoId/subtasks").then().statusCode(HTTP_CREATED).extract().path<String>("id")
        validatedRequest().get("/api/todos/$todoId/subtasks").then().statusCode(HTTP_OK)
            .body("size()", equalTo(1)).body("[0].title", equalTo("Step 1"))
        validatedRequest().get("/api/todos/$MISSING_TODO_ID/subtasks").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().contentType(ContentType.JSON).body("""{"title":"Missing"}""")
            .post("/api/todos/$MISSING_TODO_ID/subtasks").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = memberToken).contentType(ContentType.JSON).body("""{"completed":true,"position":0}""")
            .patch("/api/todos/$todoId/subtasks/$subtaskId").then().statusCode(HTTP_OK)
            .body("completed", equalTo(true)).body("position", equalTo(0))
        validatedRequest(token = memberToken).contentType(ContentType.JSON).body("""{"completed":true}""")
            .patch("/api/todos/$todoId/subtasks/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = memberToken)
            .delete("/api/todos/$todoId/subtasks/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = memberToken)
            .delete("/api/todos/$todoId/subtasks/$subtaskId").then().statusCode(HTTP_NO_CONTENT)
    }

    private fun verifyTodoCommentsFlow(todoId: String, memberToken: String) {
        val commentId = validatedRequest(token = memberToken).contentType(ContentType.JSON)
            .body("""{"content":"Looks good!"}""")
            .post("/api/todos/$todoId/comments").then().statusCode(HTTP_CREATED).extract().path<String>("id")
        validatedRequest().get("/api/todos/$todoId/comments").then().statusCode(HTTP_OK)
            .body("size()", equalTo(1)).body("[0].content", equalTo("Looks good!"))
        validatedRequest().get("/api/todos/$MISSING_TODO_ID/comments").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().contentType(ContentType.JSON).body("""{"content":"Missing"}""")
            .post("/api/todos/$MISSING_TODO_ID/comments").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().delete("/api/todos/$todoId/comments/$commentId").then().statusCode(HTTP_FORBIDDEN)
        validatedRequest(token = memberToken)
            .delete("/api/todos/$todoId/comments/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = memberToken)
            .delete("/api/todos/$todoId/comments/$commentId").then().statusCode(HTTP_NO_CONTENT)
    }

    private fun createProjectAs(token: String, name: String): String =
        validatedRequest(token = token).contentType(ContentType.JSON)
            .body("""{"name":"$name","description":"Desc"}""")
            .post("/api/projects").then().statusCode(HTTP_CREATED).extract().path("id")

    private fun addAndVerifyProjectMembers(projectId: String, memberToken: String, memberId: UUID) {
        validatedRequest().get("/api/projects/$MISSING_TODO_ID/members").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().contentType(ContentType.JSON).body("""{"username":"project_member","role":"MEMBER"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_CREATED)
        validatedRequest().contentType(ContentType.JSON).body("""{"username":"project_member"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_CONFLICT)
        validatedRequest().contentType(ContentType.JSON).body("""{"username":"no_such_user"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest(token = memberToken).contentType(ContentType.JSON).body("""{"username":"no_such_user"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_FORBIDDEN)
        validatedRequest(token = memberToken).get("/api/projects/$projectId/members").then().statusCode(HTTP_OK)
        validatedRequest(token = memberToken).delete("/api/projects/$projectId/members/$defaultUserId")
            .then().statusCode(HTTP_FORBIDDEN)
        validatedRequest().delete("/api/projects/$projectId/members/$defaultUserId").then().statusCode(HTTP_CONFLICT)
        validatedRequest().delete("/api/projects/$projectId/members/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().delete("/api/projects/$projectId/members/$memberId").then().statusCode(HTTP_NO_CONTENT)
        validatedRequest().contentType(ContentType.JSON).body("""{"username":"project_member","role":"OWNER"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_CREATED)
        validatedRequest().delete("/api/projects/$projectId/members/$memberId").then().statusCode(HTTP_NO_CONTENT)
    }

    private fun verifyProjectUpdateAndDeleteAuthorization(projectId: String, memberToken: String) {
        validatedRequest().contentType(ContentType.JSON).body("""{"username":"project_member","role":"MEMBER"}""")
            .post("/api/projects/$projectId/members").then().statusCode(HTTP_CREATED)
        validatedRequest(token = memberToken).contentType(ContentType.JSON).body("""{"name":"Denied"}""")
            .put("/api/projects/$projectId").then().statusCode(HTTP_FORBIDDEN)
        validatedRequest().contentType(ContentType.JSON).body("""{"name":"Denied"}""")
            .put("/api/projects/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().contentType(ContentType.JSON).body("""{"name":"Renamed","description":null}""")
            .put("/api/projects/$projectId").then().statusCode(HTTP_OK)
        validatedRequest(token = memberToken).delete("/api/projects/$projectId").then().statusCode(HTTP_FORBIDDEN)
        validatedRequest().delete("/api/projects/$MISSING_TODO_ID").then().statusCode(HTTP_NOT_FOUND)
        validatedRequest().delete("/api/projects/$projectId").then().statusCode(HTTP_NO_CONTENT)
    }

    private fun validatedRequest(token: String? = defaultToken, adminKey: String? = null) =
        ContainerApiClient.validatedRequest(openApiCoverageFilter, token = token, adminKey = adminKey)

    private fun validatedAdminRequest(adminKey: String? = ContainerTestConfig.adminApiKey) =
        validatedRequest(token = null, adminKey = adminKey)

    private fun validatedPostRequest(
        idempotencyKey: String = UUID.randomUUID().toString(),
        token: String? = defaultToken,
    ) = validatedRequest(token).header("Idempotency-Key", idempotencyKey)

    private companion object {
        const val INVALID_ADMIN_KEY = "wrong-admin-key"
        const val DEFAULT_TOKEN_EXPIRES_IN = 86400
        const val ESTIMATED_MINUTES = 30
        const val UPDATED_ESTIMATED_MINUTES = 45
        const val MIN_ESTIMATED_MINUTES = 0
        val MISSING_TODO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val coverageDefinition = OpenApiCoverageDefinition.from(ContainerOpenApiSpec.openAPI)
        val openApiCoverageFilter = OpenApiCoverageFilter(coverageDefinition)

        @JvmStatic
        @AfterAll
        fun reportAndVerifyOpenApiCoverage() {
            val report = OpenApiCoverageReporter.buildReport(
                coverageDefinition,
                openApiCoverageFilter.coveredResponses,
                openApiCoverageFilter.coveredConstraints,
                openApiCoverageFilter.coveredParameters,
            )
            println(OpenApiCoverageReporter.formatConsoleSummary(report))

            val missingResponses = coverageDefinition.responses - openApiCoverageFilter.coveredResponses
            assertTrue(
                missingResponses.isEmpty(),
                "The following OpenAPI responses were not exercised in container integration tests: " +
                    missingResponses.joinToString { it.toString() },
            )

            val missingParameters = coverageDefinition.parameters - openApiCoverageFilter.coveredParameters
            assertTrue(
                missingParameters.isEmpty(),
                "The following OpenAPI parameters were not exercised in container integration tests: " +
                    missingParameters.joinToString { it.toString() },
            )

            val missingConstraints = coverageDefinition.constraints - openApiCoverageFilter.coveredConstraints
            assertTrue(
                missingConstraints.isEmpty(),
                "The following OpenAPI validation constraints were not exercised in container integration tests: " +
                    missingConstraints.joinToString { it.toString() },
            )
        }
    }
}
