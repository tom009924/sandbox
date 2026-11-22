package com.example.todo

import io.swagger.v3.oas.models.PathItem.HttpMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiCoverageReporterTest {
    @Test
    fun buildReportCalculatesCorrectMetricsAndFormatsPartialCoverage() {
        val op1 = OpenApiResponse("getTodos", HttpMethod.GET, "/api/todos", STATUS_OK)
        val op2 = OpenApiResponse("getTodos", HttpMethod.GET, "/api/todos", STATUS_UNAUTHORIZED)
        val op3 = OpenApiResponse("createTodo", HttpMethod.POST, "/api/todos", STATUS_CREATED)

        val param1 = OpenApiParameter("getTodos", HttpMethod.GET, "/api/todos", "completed", "query", false)
        val param2 = OpenApiParameter("createTodo", HttpMethod.POST, "/api/todos", "Idempotency-Key", "header", true)

        val prop = OpenApiProperty(HttpMethod.POST, "/api/todos", "title")
        val constraint1 = OpenApiConstraint.Required(prop)
        val constraint2 = OpenApiConstraint.Pattern(prop, ".*\\S.*")

        val definition = OpenApiCoverageDefinition(
            responses = setOf(op1, op2, op3),
            constraints = setOf(constraint1, constraint2),
            parameters = setOf(param1, param2),
        )

        // Only op1 (200), param1, and constraint1 are covered
        val coveredResponses = setOf(op1)
        val coveredConstraints = setOf(constraint1)
        val coveredParameters = setOf(param1)

        val report = OpenApiCoverageReporter.buildReport(
            definition,
            coveredResponses,
            coveredConstraints,
            coveredParameters,
        )

        assertEquals(2, report.totalOperations)
        assertEquals(0, report.coveredOperations) // Neither getTodos nor createTodo is fully covered
        assertEquals(EXPECTED_TOTAL_RESPONSES, report.totalResponses)
        assertEquals(1, report.coveredResponses)
        assertEquals(2, report.totalParameters)
        assertEquals(1, report.coveredParameters)
        assertEquals(2, report.totalConstraints)
        assertEquals(1, report.coveredConstraints)

        val consoleSummary = OpenApiCoverageReporter.formatConsoleSummary(report)
        assertTrue(consoleSummary.contains("OpenAPI Specification Coverage Report"))
        assertTrue(consoleSummary.contains("Parameters:   1 / 2"))
        assertTrue(consoleSummary.contains("getTodos"))
        assertTrue(consoleSummary.contains("q:completed"))
        assertTrue(consoleSummary.contains("!h:Idempotency-Key"))
        assertTrue(consoleSummary.contains("createTodo"))
        assertTrue(consoleSummary.contains("✗")) // Has uncovered items
    }

    @Test
    fun buildReportFullCoverageDisplaysCheckmarks() {
        val op = OpenApiResponse("getStats", HttpMethod.GET, "/api/admin/stats", STATUS_OK)
        val param = OpenApiParameter("getStats", HttpMethod.GET, "/api/admin/stats", "key", "header", true)
        val definition = OpenApiCoverageDefinition(
            responses = setOf(op),
            constraints = emptySet(),
            parameters = setOf(param),
        )
        val report = OpenApiCoverageReporter.buildReport(
            definition,
            setOf(op),
            emptySet(),
            setOf(param),
        )

        assertEquals(1, report.totalOperations)
        assertEquals(1, report.coveredOperations)
        assertEquals(STATUS_OK_PERCENT, report.operationCoveragePercent)
        assertEquals(1, report.totalParameters)
        assertEquals(1, report.coveredParameters)
        assertEquals(STATUS_OK_PERCENT, report.parameterCoveragePercent)

        val consoleSummary = OpenApiCoverageReporter.formatConsoleSummary(report)
        assertTrue(consoleSummary.contains("✓"))
        assertTrue(consoleSummary.contains("getStats"))
        assertTrue(consoleSummary.contains("h:key"))
    }

    private companion object {
        const val STATUS_OK = 200
        const val STATUS_CREATED = 201
        const val STATUS_UNAUTHORIZED = 401
        const val EXPECTED_TOTAL_RESPONSES = 3
        const val STATUS_OK_PERCENT = 100.0
    }
}
