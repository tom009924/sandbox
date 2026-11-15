package com.example.todo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BehavioralCoverageTrackerTest {
    @Test
    fun verifyAllCoveredFailsWhenObligationsAreMissing() {
        val tracker = BehavioralCoverageTracker()
        tracker.record(
            obligation = BehavioralObligation.IDEMPOTENT_NEW_CREATE,
            observedDbWrites = 2,
            observedPublishedEvents = 1,
        )

        val error = assertThrows(AssertionError::class.java) {
            tracker.verifyAllCovered()
        }
        assertTrue(error.message.orEmpty().contains("IDEMPOTENT_REPLAY"))
        assertTrue(error.message.orEmpty().contains("LAST_PROJECT_OWNER_PROTECTED"))
    }

    @Test
    fun recordRejectsSideEffectMismatch() {
        val tracker = BehavioralCoverageTracker()

        // Idempotent replay must not write to DB or emit Pub/Sub events
        assertThrows(IllegalArgumentException::class.java) {
            tracker.record(
                obligation = BehavioralObligation.IDEMPOTENT_REPLAY,
                observedDbWrites = 0,
                observedPublishedEvents = 1,
            )
        }
    }

    @Test
    fun verifyAllCoveredPassesWhenAllObligationsAreRecordedWithValidSideEffects() {
        val tracker = BehavioralCoverageTracker()
        BehavioralObligation.entries.forEach { obligation ->
            tracker.record(
                obligation = obligation,
                observedDbWrites = obligation.expectedDbWrites.first,
                observedPublishedEvents = obligation.expectedPublishedEvents.first,
            )
        }

        tracker.verifyAllCovered()
        assertEquals(BehavioralObligation.entries.size, tracker.traceEntries().size)
    }
}
