package com.example.integration.mbt

import com.example.integration.support.ContainerOpenApiSpec
import com.example.integration.support.ContainerTestConfig
import com.example.integration.support.SpannerDatabaseCleaner
import com.example.integration.support.WireMockAdminClient
import io.restassured.RestAssured.given
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

/**
 * Model-based integration test driven directly by the OMG DMN 1.3 decision model
 * (`docs/models/todo-decisions.dmn`), executed against the real containerized microservices stack.
 */
@Tag("property")
@Suppress("TooManyFunctions")
class ContainerDmnModelBasedTest {

    private lateinit var dmnModel: DmnModelSpec

    @BeforeEach
    fun setup() {
        SpannerDatabaseCleaner.cleanAll()
        WireMockAdminClient.resetRequests()
        dmnModel = DmnModelLoader.load(Path.of(DMN_MODEL_PATH))
    }

    @Test
    fun activeTodoLimitDecisionMatchesEveryDmnRule() {
        val decision = dmnModel.decisions.getValue("activeTodoLimitDecision")
        val verifiedRuleIds = mutableSetOf<String>()

        for (rule in decision.rules) {
            val expectedStatus = rule.outputs.getValue("httpStatus").toInt()
            val actualStatus = executeActiveTodoLimitRule(rule)
            assertEquals(expectedStatus, actualStatus, "Rule ${rule.id} expected HTTP $expectedStatus")
            verifiedRuleIds.add(rule.id)
        }

        assertEquals(decision.rules.map { it.id }.toSet(), verifiedRuleIds)
    }

    @Test
    fun projectMemberRemovalDecisionMatchesEveryDmnRule() {
        val decision = dmnModel.decisions.getValue("projectMemberRemovalDecision")
        val verifiedRuleIds = mutableSetOf<String>()

        for (rule in decision.rules) {
            val expectedStatus = rule.outputs.getValue("httpStatus").toInt()
            val actualStatus = executeProjectMemberRemovalRule(rule)
            assertEquals(expectedStatus, actualStatus, "Rule ${rule.id} expected HTTP $expectedStatus")
            verifiedRuleIds.add(rule.id)
        }

        assertEquals(decision.rules.map { it.id }.toSet(), verifiedRuleIds)
    }

    @Test
    fun idempotencyDecisionMatchesEveryDmnRule() {
        val decision = dmnModel.decisions.getValue("idempotencyDecision")
        val verifiedRuleIds = mutableSetOf<String>()
        val (_, spec) = registerUserAndSpec("dmn_idem_user")
        val key = "dmn-idem-${UUID.randomUUID()}"
        val originalPayload = mapOf("title" to "Idempotent Todo")

        val createRule = decision.rules.first { it.id == "idem_create_new" }
        val firstResponse = postTodo(spec, key, originalPayload)
        assertEquals(createRule.outputs.getValue("httpStatus").toInt(), firstResponse.statusCode)
        val firstId = firstResponse.jsonPath().getString("id")
        verifiedRuleIds.add(createRule.id)

        val replayRule = decision.rules.first { it.id == "idem_replay_existing" }
        val replayResponse = postTodo(spec, key, originalPayload)
        assertEquals(replayRule.outputs.getValue("httpStatus").toInt(), replayResponse.statusCode)
        assertEquals(firstId, replayResponse.jsonPath().getString("id"))
        verifiedRuleIds.add(replayRule.id)

        val conflictRule = decision.rules.first { it.id == "idem_conflict" }
        val conflictResponse = postTodo(spec, key, mapOf("title" to "Different Payload"))
        assertEquals(conflictRule.outputs.getValue("httpStatus").toInt(), conflictResponse.statusCode)
        verifiedRuleIds.add(conflictRule.id)

        assertEquals(decision.rules.map { it.id }.toSet(), verifiedRuleIds)
    }

    @Test
    fun todoAssigneeDecisionMatchesEveryDmnRule() {
        val decision = dmnModel.decisions.getValue("todoAssigneeDecision")
        val verifiedRuleIds = mutableSetOf<String>()

        for (rule in decision.rules) {
            val expectedStatus = rule.outputs.getValue("httpStatus").toInt()
            val actualStatus = executeTodoAssigneeRule(rule)
            val normalizedActual = if (actualStatus == HTTP_CREATED) HTTP_OK else actualStatus
            assertEquals(expectedStatus, normalizedActual, "Rule ${rule.id} expected HTTP $expectedStatus")
            verifiedRuleIds.add(rule.id)
        }

        assertEquals(decision.rules.map { it.id }.toSet(), verifiedRuleIds)
    }

    private fun executeActiveTodoLimitRule(rule: DmnRuleSpec): Int {
        val (_, spec) = registerUserAndSpec(shortUsername("dmn_a"))
        updateMaxActiveTodos(spec, maxActiveTodos = 1)

        return when (rule.id) {
            "active_project_exempt" -> {
                createActiveTodo(spec, "Existing active")
                val projectId = createProject(spec, "Exempt Project")
                val status = postTodo(
                    spec,
                    UUID.randomUUID().toString(),
                    mapOf("title" to "Project Todo", "projectId" to projectId.toString()),
                ).statusCode
                if (status == HTTP_CREATED) HTTP_OK else status
            }
            "active_create_limit_exceeded" -> {
                createActiveTodo(spec, "At limit")
                postTodo(spec, UUID.randomUUID().toString(), mapOf("title" to "Blocked")).statusCode
            }
            "active_create_allowed" ->
                postTodo(spec, UUID.randomUUID().toString(), mapOf("title" to "Allowed")).statusCode
            "active_update_reopen_limit_exceeded" -> {
                val completedId = createCompletedTodo(spec, "Completed")
                createActiveTodo(spec, "At limit")
                putTodo(spec, completedId, mapOf("title" to "Reopen", "completed" to false))
            }
            "active_update_reopen_allowed" -> {
                val completedId = createCompletedTodo(spec, "Completed")
                putTodo(spec, completedId, mapOf("title" to "Reopen", "completed" to false))
            }
            "active_patch_reopen_limit_exceeded" -> {
                val completedId = createCompletedTodo(spec, "Completed")
                createActiveTodo(spec, "At limit")
                patchTodo(spec, completedId, mapOf("completed" to false))
            }
            "active_patch_reopen_allowed" -> {
                val completedId = createCompletedTodo(spec, "Completed")
                patchTodo(spec, completedId, mapOf("completed" to false))
            }
            "active_default_allowed" -> {
                val activeId = createActiveTodo(spec, "Active")
                patchTodo(spec, activeId, mapOf("completed" to true))
            }
            else -> error("Unhandled activeTodoLimitDecision rule: ${rule.id}")
        }
    }

    private fun executeProjectMemberRemovalRule(rule: DmnRuleSpec): Int {
        val ownerUsername = shortUsername("dmn_o")
        val memberUsername = shortUsername("dmn_m")
        val (ownerId, ownerSpec) = registerUserAndSpec(ownerUsername)
        val (memberId, memberSpec) = registerUserAndSpec(memberUsername)
        val projectId = createProject(ownerSpec, "Project ${rule.id}")
        addMember(ownerSpec, projectId, memberUsername)

        return when (rule.id) {
            "member_non_owner_cannot_remove_others" -> deleteMember(memberSpec, projectId, ownerId)
            "member_target_not_found" -> deleteMember(ownerSpec, projectId, UUID.randomUUID())
            "member_last_owner_protected" -> deleteMember(ownerSpec, projectId, ownerId)
            "member_removal_allowed" -> deleteMember(memberSpec, projectId, memberId)
            else -> error("Unhandled projectMemberRemovalDecision rule: ${rule.id}")
        }
    }

    private fun executeTodoAssigneeRule(rule: DmnRuleSpec): Int {
        val ownerUsername = shortUsername("dmn_ao")
        val memberUsername = shortUsername("dmn_am")
        val outsiderUsername = shortUsername("dmn_ax")
        val (ownerId, ownerSpec) = registerUserAndSpec(ownerUsername)
        val (memberId, _) = registerUserAndSpec(memberUsername)
        val (outsiderId, _) = registerUserAndSpec(outsiderUsername)
        val projectId = createProject(ownerSpec, "Assign ${rule.id}")
        addMember(ownerSpec, projectId, memberUsername)

        val payload: Map<String, Any> = when (rule.id) {
            "assignee_unassigned_allowed" -> mapOf("title" to "Unassigned")
            "assignee_personal_todo_denied" -> mapOf("title" to "Personal", "assigneeId" to ownerId.toString())
            "assignee_non_member_denied" -> mapOf(
                "title" to "Outsider",
                "projectId" to projectId.toString(),
                "assigneeId" to outsiderId.toString(),
            )
            "assignee_project_member_allowed" -> mapOf(
                "title" to "Assigned",
                "projectId" to projectId.toString(),
                "assigneeId" to memberId.toString(),
            )
            else -> error("Unhandled todoAssigneeDecision rule: ${rule.id}")
        }
        return postTodo(ownerSpec, UUID.randomUUID().toString(), payload).statusCode
    }

    private fun registerUserAndSpec(username: String): Pair<UUID, RequestSpecification> {
        val password = "password123"
        val userId = given()
            .baseUri(ContainerTestConfig.gatewayBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .`when`()
            .post("/api/auth/register")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")

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

        val spec = RequestSpecBuilder()
            .setBaseUri(ContainerTestConfig.gatewayBaseUrl)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(ContainerOpenApiSpec.validationFilter)
            .build()
        return UUID.fromString(userId) to spec
    }

    private fun updateMaxActiveTodos(spec: RequestSpecification, maxActiveTodos: Int) {
        given()
            .spec(spec)
            .body(
                ContainerOpenApiSpec.objectMapper.writeValueAsString(
                    mapOf("defaultPriority" to "MEDIUM", "maxActiveTodos" to maxActiveTodos),
                ),
            )
            .`when`()
            .put("/api/user/settings")
            .then()
            .statusCode(HTTP_OK)
    }

    private fun createProject(ownerSpec: RequestSpecification, name: String): UUID {
        val id = given()
            .spec(ownerSpec)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(mapOf("name" to name)))
            .`when`()
            .post("/api/projects")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        return UUID.fromString(id)
    }

    private fun addMember(ownerSpec: RequestSpecification, projectId: UUID, username: String) {
        given()
            .spec(ownerSpec)
            .body(
                ContainerOpenApiSpec.objectMapper.writeValueAsString(
                    mapOf("username" to username, "role" to "MEMBER"),
                ),
            )
            .`when`()
            .post("/api/projects/$projectId/members")
            .then()
            .statusCode(HTTP_CREATED)
    }

    private fun createActiveTodo(spec: RequestSpecification, title: String): UUID {
        val id = postTodo(spec, UUID.randomUUID().toString(), mapOf("title" to title))
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("id")
        return UUID.fromString(id)
    }

    private fun createCompletedTodo(spec: RequestSpecification, title: String): UUID {
        val id = createActiveTodo(spec, title)
        assertEquals(HTTP_OK, patchTodo(spec, id, mapOf("completed" to true)))
        return id
    }

    private fun postTodo(spec: RequestSpecification, key: String, payload: Map<String, Any>) =
        given()
            .spec(spec)
            .header("Idempotency-Key", key)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(payload))
            .`when`()
            .post("/api/todos")
            .then()
            .extract()
            .response()

    private fun putTodo(spec: RequestSpecification, todoId: UUID, payload: Map<String, Any>): Int =
        given()
            .spec(spec)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(payload))
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .extract()
            .statusCode()

    private fun patchTodo(spec: RequestSpecification, todoId: UUID, payload: Map<String, Any>): Int =
        given()
            .spec(spec)
            .body(ContainerOpenApiSpec.objectMapper.writeValueAsString(payload))
            .`when`()
            .patch("/api/todos/$todoId")
            .then()
            .extract()
            .statusCode()

    private fun deleteMember(spec: RequestSpecification, projectId: UUID, targetUserId: UUID): Int =
        given()
            .spec(spec)
            .`when`()
            .delete("/api/projects/$projectId/members/$targetUserId")
            .then()
            .extract()
            .statusCode()

    private fun shortUsername(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(SHORT_SUFFIX_LEN)}"

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val SHORT_SUFFIX_LEN = 8
        const val DMN_MODEL_PATH = "docs/models/todo-decisions.dmn"
    }
}
