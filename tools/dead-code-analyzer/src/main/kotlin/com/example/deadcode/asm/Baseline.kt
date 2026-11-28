package com.example.deadcode.asm

import java.io.File

data class BaselineDiff(
    val newFindings: List<String>,
    val staleEntries: List<String>,
    val baselineSize: Int,
)

object Baseline {
    private const val HEADER = "# Production dead-code baseline (ASM analyzer). Regenerate with --update-baseline.\n"

    fun parse(lines: List<String>): Set<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    fun read(file: File): Set<String> = if (file.exists()) parse(file.readLines()) else emptySet()

    fun write(file: File, findings: List<String>) {
        file.parentFile?.mkdirs()
        val body = findings.joinToString("\n") + if (findings.isNotEmpty()) "\n" else ""
        file.writeText(HEADER + body)
    }

    fun diff(findings: List<String>, baseline: Set<String>): BaselineDiff {
        val currentSet = findings.toSet()
        return BaselineDiff(
            newFindings = findings.filterNot { it in baseline },
            staleEntries = baseline.filterNot { it in currentSet }.sorted(),
            baselineSize = baseline.size,
        )
    }
}
