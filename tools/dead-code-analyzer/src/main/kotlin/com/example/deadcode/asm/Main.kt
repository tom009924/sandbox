package com.example.deadcode.asm

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
    val baselinePath = args.indexOf("--baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val updateBaselinePath = args.indexOf("--update-baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val analyzer = AsmDeadCodeAnalyzer()
    val inputs = analyzer.discoverProductionClasses(rootDir)
    val report = analyzer.analyze(inputs)

    println("ASM Dead Code Report (src/main only, Spring Bean-aware): ${report.scannedClasses} classes | ${report.elapsedMs} ms")
    println("Total findings: ${report.findings.size} (${report.deadClasses.size} classes/beans, ${report.deadMethods.size} methods)")

    if (updateBaselinePath != null) {
        val out = File(updateBaselinePath)
        Baseline.write(out, report.findings)
        println("Updated baseline (${report.findings.size} entries) at ${out.path}")
        return
    }

    if (baselinePath != null) {
        val diff = Baseline.diff(report.findings, Baseline.read(File(baselinePath)))
        if (diff.staleEntries.isNotEmpty()) {
            println("Stale baseline entries (${diff.staleEntries.size} - can be removed):")
            diff.staleEntries.forEach { println("  - $it") }
        }
        if (diff.newFindings.isNotEmpty()) {
            println("NEW production dead code detected (${diff.newFindings.size}):")
            diff.newFindings.forEach { println("  + $it") }
            exitProcess(1)
        }
        println("OK: No new production dead code beyond baseline (${diff.baselineSize} baselined).")
    } else {
        report.findings.forEach { println("  ${it}") }
    }
}
