package com.example.todo.mbt

import com.example.todo.domain.model.AuthenticatedUser
import com.example.todo.domain.model.ProjectRole
import com.example.todo.domain.model.Todo
import com.example.todo.domain.model.TodoPriority
import com.example.todo.domain.model.User
import com.example.todo.domain.model.UserSettings
import com.example.todo.domain.repository.ProjectMemberRepository
import com.example.todo.domain.repository.ProjectRepository
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.domain.repository.UserRepository
import com.example.todo.domain.repository.UserSettingsRepository
import com.example.todo.infrastructure.security.JwtTokenProvider
import com.example.todo.support.IntegrationDatabaseCleaner
import com.example.todo.support.SharedEmailWireMock
import com.example.todo.support.SharedOpenApiSpec
import io.restassured.RestAssured.given
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
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
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Model-based integration test driven directly by the OMG DMN 1.3 decision model
 * (`docs/models/todo-decisions.dmn`). Verifies every rule row against real DB state
 * and OpenAPI contract compliance via [SharedOpenApiSpec.validationFilter].
 */
@Tag("property")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["identity.service.enabled=false"],
)
@Suppress("LongParameterList", "TooManyFunctions")
class DmnTodoModelBasedIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val databaseCleaner: IntegrationDatabaseCleaner,
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper,
) {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var dmnModel: DmnModelSpec

    @BeforeEach
    fun setup() {
        databaseCleaner.cleanAll()
        SharedEmailWireMock.resetRequests()
        dmnModel = DmnModelLoader.load(Path.of(DMN_MODEL_PATH))
    }

    @Test
    fun activeTodoLimitDecisionMatchesEveryDmnRuleAgainstRealDbState() {
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
    fun projectMemberRemovalDecisionMatchesEveryDmnRuleAgainstRealDbState() {
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
    fun idempotencyDecisionMatchesEveryDmnRuleAgainstRealDbState() {
        val decision = dmnModel.decisions.getValue("idempotencyDecision")
        val verifiedRuleIds = mutableSetOf<String>()
        val (_, spec) = createUserAndSpec("dmn_idem_user")
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
    fun todoAssigneeDecisionMatchesEveryDmnRuleAgainstRealDbState() {
        val decision = dmnModel.decisions.getValue("todoAssigneeDecision")
        val verifiedRuleIds = mutableSetOf<String>()

        for (rule in decision.rules) {
            val expectedStatus = rule.outputs.getValue("httpStatus").toInt()
            val actualStatus = executeTodoAssigneeRule(rule)
            val normalizedActual = if (actualStatus == HttpStatus.CREATED.value()) {
                HttpStatus.OK.value()
            } else {
                actualStatus
            }
            assertEquals(expectedStatus, normalizedActual, "Rule ${rule.id} expected HTTP $expectedStatus")
            verifiedRuleIds.add(rule.id)
        }

        assertEquals(decision.rules.map { it.id }.toSet(), verifiedRuleIds)
    }

    private fun executeActiveTodoLimitRule(rule: DmnRuleSpec): Int {
        val (userId, spec) = createUserAndSpec("dmn_active_${rule.id}")
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                defaultPriority = TodoPriority.MEDIUM,
                defaultEstimatedMinutes = null,
                maxActiveTodos = 1,
            ),
        )
        return when (rule.id) {
            "active_project_exempt" -> {
                saveActiveTodo(userId, "Existing active")
                val projectId = createProject(userId, "Exempt Project")
                val status = postTodo(
                    spec,
                    UUID.randomUUID().toString(),
                    mapOf("title" to "Project Todo", "projectId" to projectId.toString()),
                ).statusCode
                if (status == HttpStatus.CREATED.value()) HttpStatus.OK.value() else status
            }
            "active_create_limit_exceeded" -> {
                saveActiveTodo(userId, "At limit")
                postTodo(spec, UUID.randomUUID().toString(), mapOf("title" to "Blocked")).statusCode
            }
            "active_create_allowed" ->
                postTodo(spec, UUID.randomUUID().toString(), mapOf("title" to "Allowed")).statusCode
            "active_update_reopen_limit_exceeded" -> {
                saveActiveTodo(userId, "At limit")
                val completedId = saveCompletedTodo(userId, "Completed")
                putTodo(spec, completedId, mapOf("title" to "Reopen", "completed" to false))
            }
            "active_update_reopen_allowed" -> {
                val completedId = saveCompletedTodo(userId, "Completed")
                putTodo(spec, completedId, mapOf("title" to "Reopen", "completed" to false))
            }
            "active_patch_reopen_limit_exceeded" -> {
                saveActiveTodo(userId, "At limit")
                val completedId = saveCompletedTodo(userId, "Completed")
                patchTodo(spec, completedId, mapOf("completed" to false))
            }
            "active_patch_reopen_allowed" -> {
                val completedId = saveCompletedTodo(userId, "Completed")
                patchTodo(spec, completedId, mapOf("completed" to false))
            }
            "active_default_allowed" -> {
                val activeId = saveActiveTodo(userId, "Active")
                patchTodo(spec, activeId, mapOf("completed" to true))
            }
            else -> error("Unhandled activeTodoLimitDecision rule: ${rule.id}")
        }
    }

    private fun executeProjectMemberRemovalRule(rule: DmnRuleSpec): Int {
        val (ownerId, ownerSpec) = createUserAndSpec("dmn_owner_${rule.id}")
        val (memberId, memberSpec) = createUserAndSpec("dmn_member_${rule.id}")
        val projectId = createProject(ownerId, "Project ${rule.id}")
        addMember(projectId, memberId, ProjectRole.MEMBER)

        return when (rule.id) {
            "member_non_owner_cannot_remove_others" -> deleteMember(memberSpec, projectId, ownerId)
            "member_target_not_found" -> deleteMember(ownerSpec, projectId, UUID.randomUUID())
            "member_last_owner_protected" -> deleteMember(ownerSpec, projectId, ownerId)
            "member_removal_allowed" -> deleteMember(memberSpec, projectId, memberId)
            else -> error("Unhandled projectMemberRemovalDecision rule: ${rule.id}")
        }
    }

    private fun executeTodoAssigneeRule(rule: DmnRuleSpec): Int {
        val (ownerId, ownerSpec) = createUserAndSpec("dmn_assign_owner_${rule.id}")
        val (memberId, _) = createUserAndSpec("dmn_assign_member_${rule.id}")
        val (outsiderId, _) = createUserAndSpec("dmn_assign_outsider_${rule.id}")
        val projectId = createProject(ownerId, "Assign ${rule.id}")
        addMember(projectId, memberId, ProjectRole.MEMBER)

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

    private fun createUserAndSpec(username: String): Pair<UUID, RequestSpecification> {
        val saved = userRepository.save(User(username = username, passwordHash = "hash"))
        val userId = requireNotNull(saved.id)
        val token = jwtTokenProvider.generateToken(AuthenticatedUser(userId, saved.username))
        val spec = RequestSpecBuilder()
            .setPort(port)
            .addHeader("Authorization", "Bearer $token")
            .setContentType(ContentType.JSON)
            .addFilter(SharedOpenApiSpec.validationFilter)
            .build()
        return userId to spec
    }

    private fun createProject(ownerId: UUID, name: String): UUID {
        val saved = projectRepository.save(
            com.example.todo.domain.model.Project(
                name = name,
                description = null,
                createdBy = ownerId,
                createdAt = Instant.now(),
            ),
        )
        val projectId = requireNotNull(saved.id)
        addMember(projectId, ownerId, ProjectRole.OWNER)
        return projectId
    }

    private fun addMember(projectId: UUID, userId: UUID, role: ProjectRole) {
        projectMemberRepository.save(
            com.example.todo.domain.model.ProjectMember(
                projectId = projectId,
                userId = userId,
                role = role,
                joinedAt = Instant.now(),
            ),
        )
    }

    private fun saveActiveTodo(userId: UUID, title: String): UUID =
        requireNotNull(todoRepository.save(Todo(userId = userId, title = title, completed = false)).id)

    private fun saveCompletedTodo(userId: UUID, title: String): UUID =
        requireNotNull(todoRepository.save(Todo(userId = userId, title = title, completed = true)).id)

    private fun postTodo(spec: RequestSpecification, key: String, payload: Map<String, Any>) =
        given()
            .spec(spec)
            .header("Idempotency-Key", key)
            .body(objectMapper.writeValueAsString(payload))
            .`when`()
            .post("/api/todos")
            .then()
            .extract()
            .response()

    private fun putTodo(spec: RequestSpecification, todoId: UUID, payload: Map<String, Any>): Int =
        given()
            .spec(spec)
            .body(objectMapper.writeValueAsString(payload))
            .`when`()
            .put("/api/todos/$todoId")
            .then()
            .extract()
            .statusCode()

    private fun patchTodo(spec: RequestSpecification, todoId: UUID, payload: Map<String, Any>): Int =
        given()
            .spec(spec)
            .body(objectMapper.writeValueAsString(payload))
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

    private companion object {
        const val DMN_MODEL_PATH = "docs/models/todo-decisions.dmn"

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            SharedEmailWireMock.registerProperties(registry)
        }
    }
}
