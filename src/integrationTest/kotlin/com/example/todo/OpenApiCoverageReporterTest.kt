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

        val prop = OpenApiProperty(HttpMethod.POST, "/api/todos", "title")
        val constraint1 = OpenApiConstraint.Required(prop)
        val constraint2 = OpenApiConstraint.Pattern(prop, ".*\\S.*")

        val definition = OpenApiCoverageDefinition(
            responses = setOf(op1, op2, op3),
            constraints = setOf(constraint1, constraint2),
        )

        // Only op1 (200) and constraint1 are covered
        val coveredResponses = setOf(op1)
        val coveredConstraints = setOf(constraint1)

        val report = OpenApiCoverageReporter.buildReport(definition, coveredResponses, coveredConstraints)

        assertEquals(2, report.totalOperations)
        assertEquals(0, report.coveredOperations) // Neither getTodos nor createTodo is fully covered
        assertEquals(EXPECTED_TOTAL_RESPONSES, report.totalResponses)
        assertEquals(1, report.coveredResponses)
        assertEquals(2, report.totalConstraints)
        assertEquals(1, report.coveredConstraints)

        val consoleSummary = OpenApiCoverageReporter.formatConsoleSummary(report)
        assertTrue(consoleSummary.contains("OpenAPI Specification Coverage Report"))
        assertTrue(consoleSummary.contains("getTodos"))
        assertTrue(consoleSummary.contains("createTodo"))
        assertTrue(consoleSummary.contains("✗")) // Has uncovered items
    }

    @Test
    fun buildReportFullCoverageDisplaysCheckmarks() {
        val op = OpenApiResponse("getStats", HttpMethod.GET, "/api/admin/stats", STATUS_OK)
        val definition = OpenApiCoverageDefinition(responses = setOf(op), constraints = emptySet())
        val report = OpenApiCoverageReporter.buildReport(definition, setOf(op), emptySet())

        assertEquals(1, report.totalOperations)
        assertEquals(1, report.coveredOperations)
        assertEquals(STATUS_OK_PERCENT, report.operationCoveragePercent)

        val consoleSummary = OpenApiCoverageReporter.formatConsoleSummary(report)
        assertTrue(consoleSummary.contains("✓"))
        assertTrue(consoleSummary.contains("getStats"))
    }

    private companion object {
        const val STATUS_OK = 200
        const val STATUS_CREATED = 201
        const val STATUS_UNAUTHORIZED = 401
        const val EXPECTED_TOTAL_RESPONSES = 3
        const val STATUS_OK_PERCENT = 100.0
    }
}
