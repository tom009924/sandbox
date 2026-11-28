package com.example.deadcode.analysisapi

import java.io.File

data class BaselineDiff(
    val newFindings: List<Finding>,
    val staleEntries: List<String>,
    val baselineSize: Int,
)

object Baseline {
    private const val HEADER = "# Production dead-code baseline (Kotlin Analysis API analyzer). Regenerate with --update-baseline.\n"

    fun parse(lines: List<String>): Set<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    fun read(file: File): Set<String> = if (file.exists()) parse(file.readLines()) else emptySet()

    fun write(file: File, findings: List<Finding>) {
        file.parentFile?.mkdirs()
        val body = findings.joinToString("\n") { it.key } + if (findings.isNotEmpty()) "\n" else ""
        file.writeText(HEADER + body)
    }

    fun diff(findings: List<Finding>, baseline: Set<String>): BaselineDiff {
        val currentKeys = findings.map { it.key }.toSet()
        return BaselineDiff(
            newFindings = findings.filterNot { it.key in baseline },
            staleEntries = baseline.filterNot { it in currentKeys }.sorted(),
            baselineSize = baseline.size,
        )
    }
}
