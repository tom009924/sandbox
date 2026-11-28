package com.example.deadcode.asm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BaselineTest {
    @Test
    fun `parses baseline ignoring comments and blank lines`() {
        val parsed = Baseline.parse(
            listOf(
                "# header comment",
                "",
                "  method:[:svc] com.example.A.b()V  ",
                "class:[:svc] com.example.Dead",
            ),
        )
        assertEquals(
            setOf("method:[:svc] com.example.A.b()V", "class:[:svc] com.example.Dead"),
            parsed,
        )
    }

    @Test
    fun `computes new findings and stale entries accurately`() {
        val baseline = setOf("a", "b")
        val findings = listOf("b", "c")

        val diff = Baseline.diff(findings, baseline)

        assertEquals(listOf("c"), diff.newFindings)
        assertEquals(listOf("a"), diff.staleEntries)
        assertEquals(2, diff.baselineSize)
    }

    @Test
    fun `writes and reads baseline file`(@TempDir tempDir: File) {
        val file = File(tempDir, "baseline.txt")
        val findings = listOf("class:[:svc] com.example.X", "method:[:svc] com.example.Y.z()V")

        Baseline.write(file, findings)
        val loaded = Baseline.read(file)

        assertEquals(findings.toSet(), loaded)
    }
}
