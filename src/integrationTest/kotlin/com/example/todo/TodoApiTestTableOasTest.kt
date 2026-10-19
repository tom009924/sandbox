package com.example.todo

import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Static (pre-implementation) OpenAPI alignment tests for the table-driven acceptance test suite.
 *
 * Because all acceptance test cases are defined declaratively in [TodoApiTestCases], these checks
 * verify 100% OpenAPI response coverage, constraint coverage, and request schema conformance
 * without starting the Spring Boot server, Cloud Spanner, or Pub/Sub.
 */
class TodoApiTestTableOasTest {

    private val coverageDefinition = TodoApiIntegrationTest.coverageDefinition
    private val testCases = TodoApiTestCases.allTestCases()
    private val staticCoverage = OpenApiCoverageFilter.fromTestCases(coverageDefinition, testCases)

    @Test
    fun verifyTestTableCoversAllOpenApiResponsesBeforeImplementation() {
        val missingResponses = coverageDefinition.responses - staticCoverage.coveredResponses
        assertTrue(
            missingResponses.isEmpty(),
            "The following OpenAPI responses are missing from TodoApiTestCases: " +
                missingResponses.joinToString { it.toString() },
        )
    }

    @Test
    fun verifyTestTableCoversAllOpenApiConstraintsBeforeImplementation() {
        val missingConstraints = coverageDefinition.constraints - staticCoverage.coveredConstraints
        assertTrue(
            missingConstraints.isEmpty(),
            "The following OpenAPI constraints are missing from TodoApiTestCases: " +
                missingConstraints.joinToString { it.toString() },
        )
    }

    @Test
    fun verifyValidTestCasesConformToOpenApiRequestSchema() {
        val validCases = testCases.filterNot { it.invalidRequest }
        validCases.forEach { tc ->
            val report = TodoApiIntegrationTest.strictValidator.validateRequest(tc.toSimpleRequest())
            assertFalse(
                report.hasErrors(),
                "Expected valid request for test case '$tc' to conform to OpenAPI schema, but found errors: " +
                    report.messages.joinToString { it.message },
            )
        }
    }

    @Test
    fun verifyInvalidTestCasesTriggerExpectedOpenApiRequestViolations() {
        val invalidCases = testCases.filter { it.invalidRequest }
        invalidCases.forEach { tc ->
            val request = tc.toSimpleRequest()
            val strictReport = TodoApiIntegrationTest.strictValidator.validateRequest(request)
            assertTrue(
                strictReport.hasErrors(),
                "Expected invalid request test case '$tc' to violate OpenAPI request schema, but no errors were found",
            )

            val whitelistedReport = TodoApiIntegrationTest.invalidRequestValidator.validateRequest(request)
            assertFalse(
                whitelistedReport.hasErrors(),
                "Expected invalid request test case '$tc' to only violate whitelisted schema constraints, " +
                    "but found unexpected errors: ${whitelistedReport.messages.joinToString { it.message }}",
            )
        }
    }

    private fun TodoApiTestCase.toSimpleRequest(): Request {
        val builder = SimpleRequest.Builder(method.name, samplePath())
        sampleHeaders().forEach { (name, value) -> builder.withHeader(name, value) }
        if (requestBody != null) {
            builder.withContentType("application/json")
            builder.withBody(requestBody)
        }
        return builder.build()
    }
}

