package com.example.todo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behavioral, state-transition, and side-effect obligation derived directly from
 * `@obligation` annotations in TLA+ files under `docs/formal/`.
 */
data class BehavioralObligation(
    val id: String,
    val operationId: String,
    val expectedDbWrites: IntRange,
    val expectedPublishedEvents: IntRange,
    val description: String,
)

data class BehavioralTraceEntry(
    val op: String,
    val obligationId: String,
    val dbWrites: Int,
    val publishedEvents: Int,
)

/**
 * Parses `@obligation <ID> | <operationId> | db=<range> | events=<range> | <description>`
 * from TLA+ formal specifications so coverage goals have a single source of truth.
 */
object FormalObligationLoader {
    private val OBLIGATION_REGEX = Regex(
        """^\\\*\s*@obligation\s+([A-Z0-9_]+)\s*\|\s*([A-Za-z0-9_]+)\s*""" +
            """\|\s*db=([^\s|]+)\s*\|\s*events=([^\s|]+)\s*\|\s*(.+)$""",
    )
    private val PLUS_SUFFIX_REGEX = Regex("""^(\d+)\+$""")
    private val RANGE_REGEX = Regex("""^(\d+)\.\.(\d+)$""")

    fun loadDefault(): List<BehavioralObligation> = loadFromDirectory(Paths.get("docs", "formal"))

    fun loadFromDirectory(dir: Path): List<BehavioralObligation> {
        require(Files.isDirectory(dir)) { "Formal specs directory not found: $dir" }
        val tlaFiles = Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".tla") }
                .sorted()
                .toList()
        }
        return tlaFiles.flatMap { path -> parse(Files.readString(path)) }
    }

    fun parse(tlaContent: String): List<BehavioralObligation> =
        tlaContent.lineSequence()
            .mapNotNull { line ->
                val match = OBLIGATION_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
                BehavioralObligation(
                    id = match.groupValues[GROUP_ID],
                    operationId = match.groupValues[GROUP_OPERATION_ID],
                    expectedDbWrites = parseRange(match.groupValues[GROUP_DB]),
                    expectedPublishedEvents = parseRange(match.groupValues[GROUP_EVENTS]),
                    description = match.groupValues[GROUP_DESCRIPTION].trim(),
                )
            }
            .toList()

    private fun parseRange(expr: String): IntRange {
        val trimmed = expr.trim()
        val plusMatch = PLUS_SUFFIX_REGEX.matchEntire(trimmed)
        val rangeMatch = RANGE_REGEX.matchEntire(trimmed)
        return when {
            plusMatch != null -> plusMatch.groupValues[1].toInt()..Int.MAX_VALUE
            rangeMatch != null -> rangeMatch.groupValues[1].toInt()..rangeMatch.groupValues[2].toInt()
            else -> trimmed.toInt().let { it..it }
        }
    }

    private const val GROUP_ID = 1
    private const val GROUP_OPERATION_ID = 2
    private const val GROUP_DB = 3
    private const val GROUP_EVENTS = 4
    private const val GROUP_DESCRIPTION = 5
}

/**
 * Tracks behavioral coverage against obligations derived from `docs/formal/`
 * and verifies side-effect consistency for each recorded obligation.
 */
class BehavioralCoverageTracker(
    obligations: List<BehavioralObligation> = FormalObligationLoader.loadDefault(),
) {
    private val obligationsById: Map<String, BehavioralObligation> = obligations.associateBy { it.id }
    private val covered = ConcurrentHashMap.newKeySet<String>()
    private val traceLog = CopyOnWriteArrayList<BehavioralTraceEntry>()

    init {
        require(obligationsById.isNotEmpty()) { "At least one @obligation must be defined in docs/formal/" }
    }

    fun record(
        obligationId: String,
        observedDbWrites: Int,
        observedPublishedEvents: Int,
    ) {
        val obligation = requireNotNull(obligationsById[obligationId]) {
            "Unknown behavioral obligation '$obligationId'. Define it in docs/formal/ first."
        }
        require(observedDbWrites in obligation.expectedDbWrites) {
            "Side-effect mismatch for ${obligation.id}: " +
                "expected dbWrites in ${obligation.expectedDbWrites}, got $observedDbWrites"
        }
        require(observedPublishedEvents in obligation.expectedPublishedEvents) {
            "Side-effect mismatch for ${obligation.id}: " +
                "expected publishedEvents in ${obligation.expectedPublishedEvents}, got $observedPublishedEvents"
        }
        covered.add(obligation.id)
        traceLog.add(
            BehavioralTraceEntry(
                op = obligation.operationId,
                obligationId = obligation.id,
                dbWrites = observedDbWrites,
                publishedEvents = observedPublishedEvents,
            ),
        )
    }

    fun allObligations(): List<BehavioralObligation> = obligationsById.values.toList()

    fun uncoveredObligations(): List<BehavioralObligation> =
        obligationsById.values.filterNot { it.id in covered }

    fun traceEntries(): List<BehavioralTraceEntry> = traceLog.toList()

    fun verifyAllCovered() {
        val uncovered = uncoveredObligations()
        if (uncovered.isNotEmpty()) {
            val details = uncovered.joinToString("\n") { "  - ${it.id} (${it.operationId}): ${it.description}" }
            throw AssertionError("Uncovered behavioral obligations:\n$details")
        }
    }
}
