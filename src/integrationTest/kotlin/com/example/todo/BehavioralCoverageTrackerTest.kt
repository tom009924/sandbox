package com.example.todo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BehavioralCoverageTrackerTest {
    @Test
    fun loadsObligationsDirectlyFromFormalTlaSpecs() {
        val obligations = FormalObligationLoader.loadDefault()
        val ids = obligations.map { it.id }.toSet()

        assertTrue("IDEMPOTENT_NEW_CREATE" in ids)
        assertTrue("IDEMPOTENT_REPLAY" in ids)
        assertTrue("ACTIVE_LIMIT_EXCEEDED_ON_REOPEN" in ids)
        assertTrue("DELETE_TODO_EMITS_EVENT" in ids)
    }

    @Test
    fun verifyAllCoveredFailsWhenObligationsAreMissing() {
        val tracker = BehavioralCoverageTracker()
        tracker.record(
            obligationId = "IDEMPOTENT_NEW_CREATE",
            observedDbWrites = 2,
            observedPublishedEvents = 1,
        )

        val error = assertThrows(AssertionError::class.java) {
            tracker.verifyAllCovered()
        }
        assertTrue(error.message.orEmpty().contains("IDEMPOTENT_REPLAY"))
        assertTrue(error.message.orEmpty().contains("ACTIVE_LIMIT_EXCEEDED_ON_REOPEN"))
    }

    @Test
    fun recordRejectsUnknownObligationAndSideEffectMismatch() {
        val tracker = BehavioralCoverageTracker()

        assertThrows(IllegalArgumentException::class.java) {
            tracker.record(
                obligationId = "UNDECLARED_OBLIGATION",
                observedDbWrites = 0,
                observedPublishedEvents = 0,
            )
        }

        // Idempotent replay must not write to DB or emit Pub/Sub events
        assertThrows(IllegalArgumentException::class.java) {
            tracker.record(
                obligationId = "IDEMPOTENT_REPLAY",
                observedDbWrites = 0,
                observedPublishedEvents = 1,
            )
        }
    }

    @Test
    fun verifyAllCoveredPassesWhenAllTlaDerivedObligationsAreRecorded() {
        val tracker = BehavioralCoverageTracker()
        tracker.allObligations().forEach { obligation ->
            tracker.record(
                obligationId = obligation.id,
                observedDbWrites = obligation.expectedDbWrites.first,
                observedPublishedEvents = obligation.expectedPublishedEvents.first,
            )
        }

        tracker.verifyAllCovered()
        assertEquals(tracker.allObligations().size, tracker.traceEntries().size)
    }
}
