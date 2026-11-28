package com.example.deadcode.analysisapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BaselineAndMetadataTest {
    @Test
    fun `parses TSV metadata lines`() {
        val meta = Metadata.parse(
            listOf(
                "S\t/path/to/src1",
                "S\t/path/to/src2",
                "C\t/path/to/lib.jar",
                "ignored-line",
            ),
        )
        assertEquals(listOf("/path/to/src1", "/path/to/src2"), meta.sourceRoots)
        assertEquals(listOf("/path/to/lib.jar"), meta.classpath)
    }

    @Test
    fun `computes baseline diff and round-trips file`(@TempDir dir: File) {
        val file = File(dir, "baseline.txt")
        val initial = listOf(Finding("class:A", "A.kt:1"), Finding("member:B.f()", "B.kt:2"))
        Baseline.write(file, initial)

        val baseline = Baseline.read(file)
        val current = listOf(Finding("member:B.f()", "B.kt:99"), Finding("member:C.g()", "C.kt:5"))
        val diff = Baseline.diff(current, baseline)

        assertEquals(listOf(Finding("member:C.g()", "C.kt:5")), diff.newFindings)
        assertEquals(listOf("class:A"), diff.staleEntries)
        assertEquals(2, diff.baselineSize)
    }
}
