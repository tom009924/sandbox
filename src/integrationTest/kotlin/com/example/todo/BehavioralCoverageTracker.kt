package com.example.todo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behavioral, state-transition, and side-effect obligations that cannot be expressed
 * in OpenAPI (docs/openapi.yaml) alone, derived from docs/formal/TodoLifecycle.tla
 * and domain invariants.
 */
enum class BehavioralObligation(
    val operationId: String,
    val expectedDbWrites: IntRange,
    val expectedPublishedEvents: IntRange,
    val description: String,
) {
    IDEMPOTENT_NEW_CREATE(
        operationId = "createTodo",
        expectedDbWrites = 1..Int.MAX_VALUE,
        expectedPublishedEvents = 1..1,
        description = "New Idempotency-Key persists Todo, IdempotencyRecord, and publishes 1 CREATED event",
    ),
    IDEMPOTENT_REPLAY(
        operationId = "createTodo",
        expectedDbWrites = 0..0,
        expectedPublishedEvents = 0..0,
        description = "Replayed Idempotency-Key with same fingerprint produces 0 DB writes and 0 events",
    ),
    IDEMPOTENT_CONFLICT(
        operationId = "createTodo",
        expectedDbWrites = 0..0,
        expectedPublishedEvents = 0..0,
        description = "Reused Idempotency-Key with different fingerprint returns 409 with 0 side effects",
    ),
    IDEMPOTENT_TTL_EXPIRED(
        operationId = "createTodo",
        expectedDbWrites = 1..Int.MAX_VALUE,
        expectedPublishedEvents = 1..1,
        description = "Expired Idempotency-Key creates a new Todo and publishes 1 CREATED event",
    ),
    ACTIVE_LIMIT_EXCEEDED_ON_CREATE(
        operationId = "createTodo",
        expectedDbWrites = 0..0,
        expectedPublishedEvents = 0..0,
        description = "Active Todo creation at maxActiveTodos limit fails with 0 side effects",
    ),
    ACTIVE_LIMIT_BYPASSED_ON_COMPLETED_CREATE(
        operationId = "createTodo",
        expectedDbWrites = 1..Int.MAX_VALUE,
        expectedPublishedEvents = 1..1,
        description = "Completed Todo creation at maxActiveTodos limit succeeds",
    ),
    ACTIVE_LIMIT_EXCEEDED_ON_REOPEN(
        operationId = "patchTodo",
        expectedDbWrites = 0..0,
        expectedPublishedEvents = 0..0,
        description = "Reopening completed Todo at maxActiveTodos limit fails with 0 side effects",
    ),
    ACTIVE_LIMIT_ALLOWED_ON_COMPLETE(
        operationId = "patchTodo",
        expectedDbWrites = 1..Int.MAX_VALUE,
        expectedPublishedEvents = 1..1,
        description = "Completing active Todo at maxActiveTodos limit succeeds",
    ),
    LAST_PROJECT_OWNER_PROTECTED(
        operationId = "removeProjectMember",
        expectedDbWrites = 0..0,
        expectedPublishedEvents = 0..0,
        description = "Removing the last OWNER of a project fails with 0 side effects",
    ),
    NON_LAST_PROJECT_OWNER_REMOVABLE(
        operationId = "removeProjectMember",
        expectedDbWrites = 1..Int.MAX_VALUE,
        expectedPublishedEvents = 0..0,
        description = "Removing one OWNER when another OWNER remains succeeds",
    ),
}

data class BehavioralTraceEntry(
    val op: String,
    val obligation: BehavioralObligation,
    val dbWrites: Int,
    val publishedEvents: Int,
)

/**
 * Tracks behavioral coverage and verifies side-effect consistency for each recorded obligation.
 * Also collects trace entries that can be validated against docs/formal/TodoLifecycleTrace.tla.
 */
class BehavioralCoverageTracker {
    private val covered = ConcurrentHashMap.newKeySet<BehavioralObligation>()
    private val traceLog = CopyOnWriteArrayList<BehavioralTraceEntry>()

    fun record(
        obligation: BehavioralObligation,
        observedDbWrites: Int,
        observedPublishedEvents: Int,
    ) {
        require(observedDbWrites in obligation.expectedDbWrites) {
            "Side-effect mismatch for ${obligation.name}: " +
                "expected dbWrites in ${obligation.expectedDbWrites}, got $observedDbWrites"
        }
        require(observedPublishedEvents in obligation.expectedPublishedEvents) {
            "Side-effect mismatch for ${obligation.name}: " +
                "expected publishedEvents in ${obligation.expectedPublishedEvents}, got $observedPublishedEvents"
        }
        covered.add(obligation)
        traceLog.add(
            BehavioralTraceEntry(
                op = obligation.operationId,
                obligation = obligation,
                dbWrites = observedDbWrites,
                publishedEvents = observedPublishedEvents,
            ),
        )
    }

    fun coveredObligations(): Set<BehavioralObligation> = covered.toSet()

    fun uncoveredObligations(): List<BehavioralObligation> =
        BehavioralObligation.entries.filterNot { it in covered }

    fun traceEntries(): List<BehavioralTraceEntry> = traceLog.toList()

    fun verifyAllCovered() {
        val uncovered = uncoveredObligations()
        if (uncovered.isNotEmpty()) {
            val details = uncovered.joinToString("\n") { "  - ${it.name} (${it.operationId}): ${it.description}" }
            throw AssertionError("Uncovered behavioral obligations:\n$details")
        }
    }
}
