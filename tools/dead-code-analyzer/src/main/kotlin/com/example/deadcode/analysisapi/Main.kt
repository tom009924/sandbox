package com.example.deadcode.analysisapi

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val metaPath = args.firstOrNull { !it.startsWith("--") } ?: error("Pass metadata TSV path")
    val baselinePath = args.indexOf("--baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val updateBaselinePath = args.indexOf("--update-baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val report = AnalysisApiDeadCodeAnalyzer().analyze(Metadata.read(File(metaPath)))

    println("Analysis API Dead Code Report (src/main only, Spring Bean-aware): ${report.scannedFiles} files, ${report.declaredCount} decls | ${report.elapsedMs} ms")
    println("Total findings: ${report.findings.size} (${report.deadClasses.size} classes/beans, ${report.deadMembers.size} members)")

    val exitCode = when {
        updateBaselinePath != null -> {
            val out = File(updateBaselinePath)
            Baseline.write(out, report.findings)
            println("Updated baseline (${report.findings.size} entries) at ${out.path}")
            0
        }
        baselinePath != null -> {
            val diff = Baseline.diff(report.findings, Baseline.read(File(baselinePath)))
            if (diff.staleEntries.isNotEmpty()) {
                println("Stale baseline entries (${diff.staleEntries.size} - can be removed):")
                diff.staleEntries.forEach { println("  - $it") }
            }
            if (diff.newFindings.isNotEmpty()) {
                println("NEW production dead code detected (${diff.newFindings.size}):")
                diff.newFindings.forEach { println("  + ${it.key} (${it.location})") }
                1
            } else {
                println("OK: No new production dead code beyond baseline (${diff.baselineSize} baselined).")
                0
            }
        }
        else -> {
            report.findings.forEach { println("  ${it.key} (${it.location})") }
            0
        }
    }
    exitProcess(exitCode)
}
