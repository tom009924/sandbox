package com.example.todo

import java.time.Instant
import kotlin.math.round

data class ResponseCoverageItem(
    val statusCode: Int,
    val isCovered: Boolean,
)

data class ConstraintCoverageItem(
    val description: String,
    val isCovered: Boolean,
)

data class OperationCoverageSummary(
    val operationId: String,
    val method: String,
    val path: String,
    val responsesTotal: Int,
    val responsesCovered: Int,
    val constraintsTotal: Int,
    val constraintsCovered: Int,
    val isFullyCovered: Boolean,
    val responses: List<ResponseCoverageItem>,
    val constraints: List<ConstraintCoverageItem>,
)

data class OpenApiCoverageReport(
    val timestamp: String,
    val totalOperations: Int,
    val coveredOperations: Int,
    val operationCoveragePercent: Double,
    val totalResponses: Int,
    val coveredResponses: Int,
    val responseCoveragePercent: Double,
    val totalConstraints: Int,
    val coveredConstraints: Int,
    val constraintCoveragePercent: Double,
    val operations: List<OperationCoverageSummary>,
)

object OpenApiCoverageReporter {
    private const val PERCENT_MULTIPLIER = 100.0
    private const val ROUND_SCALE = 10.0
    private const val PAD_TARGET = 48

    fun buildReport(
        definition: OpenApiCoverageDefinition,
        coveredResponses: Set<OpenApiResponse>,
        coveredConstraints: Set<OpenApiConstraint>,
    ): OpenApiCoverageReport {
        val operationsMap = definition.responses.groupBy { it.operationId }
        val operations = operationsMap.map { (opId, respList) ->
            val first = respList.first()
            val method = first.method
            val path = first.path

            val opResponses = respList.map { r ->
                ResponseCoverageItem(r.statusCode, r in coveredResponses)
            }.sortedBy { it.statusCode }

            val opConstraints = definition.constraints
                .filter { it.property.method == method && matchesPath(path, it.property.path) }
                .map { c ->
                    ConstraintCoverageItem(c.toString(), c in coveredConstraints)
                }

            val respCovered = opResponses.count { it.isCovered }
            val constrCovered = opConstraints.count { it.isCovered }
            val isFullyCovered = respCovered == opResponses.size &&
                (opConstraints.isEmpty() || constrCovered == opConstraints.size)

            OperationCoverageSummary(
                operationId = opId,
                method = method.name,
                path = path,
                responsesTotal = opResponses.size,
                responsesCovered = respCovered,
                constraintsTotal = opConstraints.size,
                constraintsCovered = constrCovered,
                isFullyCovered = isFullyCovered,
                responses = opResponses,
                constraints = opConstraints,
            )
        }.sortedWith(compareBy({ it.path }, { it.method }))

        val totalOps = operations.size
        val coveredOps = operations.count { it.isFullyCovered }
        val totalResp = definition.responses.size
        val coveredResp = coveredResponses.size
        val totalConstr = definition.constraints.size
        val coveredConstr = coveredConstraints.size

        return OpenApiCoverageReport(
            timestamp = Instant.now().toString(),
            totalOperations = totalOps,
            coveredOperations = coveredOps,
            operationCoveragePercent = calculatePercent(coveredOps, totalOps),
            totalResponses = totalResp,
            coveredResponses = coveredResp,
            responseCoveragePercent = calculatePercent(coveredResp, totalResp),
            totalConstraints = totalConstr,
            coveredConstraints = coveredConstr,
            constraintCoveragePercent = calculatePercent(coveredConstr, totalConstr),
            operations = operations,
        )
    }

    fun formatConsoleSummary(report: OpenApiCoverageReport): String = buildString {
        appendLine()
        appendLine("======================================================================")
        appendLine("                   OpenAPI Specification Coverage Report              ")
        appendLine("======================================================================")
        appendLine(
            "Operations:   ${report.coveredOperations} / ${report.totalOperations} " +
                "(${report.operationCoveragePercent}%)",
        )
        appendLine(
            "Responses:    ${report.coveredResponses} / ${report.totalResponses} " +
                "(${report.responseCoveragePercent}%)",
        )
        appendLine(
            "Constraints:  ${report.coveredConstraints} / ${report.totalConstraints} " +
                "(${report.constraintCoveragePercent}%)",
        )
        appendLine("----------------------------------------------------------------------")
        report.operations.forEach { op ->
            val mark = if (op.isFullyCovered) "✓" else "✗"
            val respStr = op.responses.joinToString(" ") { r ->
                if (r.isCovered) "${r.statusCode}" else "!${r.statusCode}"
            }
            val target = "${op.method} ${op.path} (${op.operationId})"
            appendLine("  $mark ${target.padEnd(PAD_TARGET)} [$respStr]")
        }
        appendLine("======================================================================")
    }

    private fun calculatePercent(covered: Int, total: Int): Double {
        if (total == 0) return PERCENT_MULTIPLIER
        val ratio = (covered.toDouble() / total) * PERCENT_MULTIPLIER * ROUND_SCALE
        return round(ratio) / ROUND_SCALE
    }
}
