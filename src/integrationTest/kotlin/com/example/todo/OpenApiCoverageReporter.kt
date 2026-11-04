package com.example.todo

import java.time.Instant
import kotlin.math.round

data class ResponseCoverageItem(
    val statusCode: Int,
    val isCovered: Boolean,
)

data class ParameterCoverageItem(
    val name: String,
    val location: String,
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
    val parametersTotal: Int,
    val parametersCovered: Int,
    val constraintsTotal: Int,
    val constraintsCovered: Int,
    val isFullyCovered: Boolean,
    val responses: List<ResponseCoverageItem>,
    val parameters: List<ParameterCoverageItem>,
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
    val totalParameters: Int,
    val coveredParameters: Int,
    val parameterCoveragePercent: Double,
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
        coveredParameters: Set<OpenApiParameter> = emptySet(),
    ): OpenApiCoverageReport {
        val ctx = CoverageContext(definition, coveredResponses, coveredConstraints, coveredParameters)
        val operations = definition.responses.groupBy { it.operationId }
            .map { (opId, respList) -> buildOperationSummary(opId, respList, ctx) }
            .sortedWith(compareBy({ it.path }, { it.method }))

        val totalOps = operations.size
        val coveredOps = operations.count { it.isFullyCovered }
        val totalResp = definition.responses.size
        val coveredResp = coveredResponses.size
        val totalParams = definition.parameters.size
        val coveredParams = coveredParameters.size
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
            totalParameters = totalParams,
            coveredParameters = coveredParams,
            parameterCoveragePercent = calculatePercent(coveredParams, totalParams),
            totalConstraints = totalConstr,
            coveredConstraints = coveredConstr,
            constraintCoveragePercent = calculatePercent(coveredConstr, totalConstr),
            operations = operations,
        )
    }

    private fun buildOperationSummary(
        opId: String,
        respList: List<OpenApiResponse>,
        ctx: CoverageContext,
    ): OperationCoverageSummary {
        val first = respList.first()
        val method = first.method
        val path = first.path

        val opResponses = respList.map { r ->
            ResponseCoverageItem(r.statusCode, r in ctx.coveredResponses)
        }.sortedBy { it.statusCode }

        val opParameters = ctx.definition.parameters
            .filter { it.operationId == opId }
            .map { p ->
                ParameterCoverageItem(p.name, p.location, p in ctx.coveredParameters)
            }.sortedBy { it.name }

        val opConstraints = ctx.definition.constraints
            .filter { it.property.method == method && matchesPath(path, it.property.path) }
            .map { c ->
                ConstraintCoverageItem(c.toString(), c in ctx.coveredConstraints)
            }

        val respCovered = opResponses.count { it.isCovered }
        val paramsCovered = opParameters.count { it.isCovered }
        val constrCovered = opConstraints.count { it.isCovered }
        val isFullyCovered = respCovered == opResponses.size &&
            (opParameters.isEmpty() || paramsCovered == opParameters.size) &&
            (opConstraints.isEmpty() || constrCovered == opConstraints.size)

        return OperationCoverageSummary(
            operationId = opId,
            method = method.name,
            path = path,
            responsesTotal = opResponses.size,
            responsesCovered = respCovered,
            parametersTotal = opParameters.size,
            parametersCovered = paramsCovered,
            constraintsTotal = opConstraints.size,
            constraintsCovered = constrCovered,
            isFullyCovered = isFullyCovered,
            responses = opResponses,
            parameters = opParameters,
            constraints = opConstraints,
        )
    }

private data class CoverageContext(
    val definition: OpenApiCoverageDefinition,
    val coveredResponses: Set<OpenApiResponse>,
    val coveredConstraints: Set<OpenApiConstraint>,
    val coveredParameters: Set<OpenApiParameter>,
)

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
            "Parameters:   ${report.coveredParameters} / ${report.totalParameters} " +
                "(${report.parameterCoveragePercent}%)",
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
            val paramStr = if (op.parameters.isNotEmpty()) {
                val params = op.parameters.joinToString(" ") { p ->
                    val prefix = when (p.location.lowercase()) {
                        "query" -> "q"
                        "header" -> "h"
                        "path" -> "p"
                        else -> p.location
                    }
                    if (p.isCovered) "$prefix:${p.name}" else "!$prefix:${p.name}"
                }
                " {$params}"
            } else {
                ""
            }
            val target = "${op.method} ${op.path} (${op.operationId})"
            appendLine("  $mark ${target.padEnd(PAD_TARGET)} [$respStr]$paramStr")
        }
        appendLine("======================================================================")
    }

    private fun calculatePercent(covered: Int, total: Int): Double {
        if (total == 0) return PERCENT_MULTIPLIER
        val ratio = (covered.toDouble() / total) * PERCENT_MULTIPLIER * ROUND_SCALE
        return round(ratio) / ROUND_SCALE
    }
}
