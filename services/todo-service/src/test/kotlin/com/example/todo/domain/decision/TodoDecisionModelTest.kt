package com.example.todo.domain.decision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TodoDecisionModelTest {

    @Test
    fun activeTodoLimitDecisionCoversAllDmnRules() {
        val cases = listOf(
            ActiveTodoLimitDecisionInput(true, "createTodo", false, false, true, true) to
                ActiveTodoLimitDecisionResult("active_project_exempt", false, "ALLOW", HTTP_OK),
            ActiveTodoLimitDecisionInput(false, "createTodo", false, false, true, true) to
                ActiveTodoLimitDecisionResult(
                    "active_create_limit_exceeded",
                    true,
                    "DENY_ACTIVE_LIMIT_EXCEEDED",
                    HTTP_BAD_REQUEST,
                ),
            ActiveTodoLimitDecisionInput(false, "createTodo", false, false, true, false) to
                ActiveTodoLimitDecisionResult("active_create_allowed", true, "ALLOW", HTTP_CREATED),
            ActiveTodoLimitDecisionInput(false, "updateTodo", true, false, true, true) to
                ActiveTodoLimitDecisionResult(
                    "active_update_reopen_limit_exceeded",
                    true,
                    "DENY_ACTIVE_LIMIT_EXCEEDED",
                    HTTP_BAD_REQUEST,
                ),
            ActiveTodoLimitDecisionInput(false, "updateTodo", true, false, true, false) to
                ActiveTodoLimitDecisionResult("active_update_reopen_allowed", true, "ALLOW", HTTP_OK),
            ActiveTodoLimitDecisionInput(false, "patchTodo", true, false, true, true) to
                ActiveTodoLimitDecisionResult(
                    "active_patch_reopen_limit_exceeded",
                    true,
                    "DENY_ACTIVE_LIMIT_EXCEEDED",
                    HTTP_BAD_REQUEST,
                ),
            ActiveTodoLimitDecisionInput(false, "patchTodo", true, false, false, false) to
                ActiveTodoLimitDecisionResult("active_patch_reopen_allowed", true, "ALLOW", HTTP_OK),
            ActiveTodoLimitDecisionInput(false, "patchTodo", false, true, true, true) to
                ActiveTodoLimitDecisionResult("active_default_allowed", false, "ALLOW", HTTP_OK),
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, TodoDecisionModel.evaluateActiveTodoLimitDecision(input))
        }
    }

    @Test
    fun projectMemberRemovalDecisionCoversAllDmnRules() {
        assertEquals(
            ProjectMemberRemovalDecisionResult(
                "member_non_owner_cannot_remove_others",
                "DENY_FORBIDDEN",
                HTTP_FORBIDDEN,
            ),
            TodoDecisionModel.evaluateProjectMemberRemovalDecision(
                ProjectMemberRemovalDecisionInput(false, "MEMBER", true, "MEMBER", false),
            ),
        )
        assertEquals(
            ProjectMemberRemovalDecisionResult("member_target_not_found", "DENY_NOT_FOUND", HTTP_NOT_FOUND),
            TodoDecisionModel.evaluateProjectMemberRemovalDecision(
                ProjectMemberRemovalDecisionInput(false, "OWNER", false, "NONE", false),
            ),
        )
        assertEquals(
            ProjectMemberRemovalDecisionResult("member_last_owner_protected", "DENY_LAST_OWNER", HTTP_CONFLICT),
            TodoDecisionModel.evaluateProjectMemberRemovalDecision(
                ProjectMemberRemovalDecisionInput(true, "OWNER", true, "OWNER", true),
            ),
        )
        assertEquals(
            ProjectMemberRemovalDecisionResult("member_removal_allowed", "ALLOW", HTTP_NO_CONTENT),
            TodoDecisionModel.evaluateProjectMemberRemovalDecision(
                ProjectMemberRemovalDecisionInput(true, "MEMBER", true, "MEMBER", false),
            ),
        )
    }

    @Test
    fun idempotencyDecisionCoversAllDmnRules() {
        assertEquals(
            IdempotencyDecisionResult("idem_replay_existing", "REPLAY_EXISTING", HTTP_CREATED),
            TodoDecisionModel.evaluateIdempotencyDecision(IdempotencyDecisionInput(true, true, true)),
        )
        assertEquals(
            IdempotencyDecisionResult("idem_conflict", "DENY_CONFLICT", HTTP_CONFLICT),
            TodoDecisionModel.evaluateIdempotencyDecision(IdempotencyDecisionInput(true, true, false)),
        )
        assertEquals(
            IdempotencyDecisionResult("idem_create_new", "CREATE_NEW", HTTP_CREATED),
            TodoDecisionModel.evaluateIdempotencyDecision(IdempotencyDecisionInput(false, false, false)),
        )
        assertEquals(
            IdempotencyDecisionResult("idem_create_new", "CREATE_NEW", HTTP_CREATED),
            TodoDecisionModel.evaluateIdempotencyDecision(IdempotencyDecisionInput(true, false, false)),
        )
    }

    @Test
    fun todoAssigneeDecisionCoversAllDmnRules() {
        assertEquals(
            TodoAssigneeDecisionResult("assignee_unassigned_allowed", "ALLOW", HTTP_OK),
            TodoDecisionModel.evaluateTodoAssigneeDecision(TodoAssigneeDecisionInput(false, false, false)),
        )
        assertEquals(
            TodoAssigneeDecisionResult("assignee_personal_todo_denied", "DENY_PERSONAL_TODO", HTTP_BAD_REQUEST),
            TodoDecisionModel.evaluateTodoAssigneeDecision(TodoAssigneeDecisionInput(true, false, false)),
        )
        assertEquals(
            TodoAssigneeDecisionResult("assignee_non_member_denied", "DENY_NOT_PROJECT_MEMBER", HTTP_BAD_REQUEST),
            TodoDecisionModel.evaluateTodoAssigneeDecision(TodoAssigneeDecisionInput(true, true, false)),
        )
        assertEquals(
            TodoAssigneeDecisionResult("assignee_project_member_allowed", "ALLOW", HTTP_OK),
            TodoDecisionModel.evaluateTodoAssigneeDecision(TodoAssigneeDecisionInput(true, true, true)),
        )
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NO_CONTENT = 204
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
    }
}
