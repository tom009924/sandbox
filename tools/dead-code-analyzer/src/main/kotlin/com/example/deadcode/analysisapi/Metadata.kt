package com.example.deadcode.analysisapi

import java.io.File

data class Metadata(
    val sourceRoots: List<String>,
    val classpath: List<String>,
) {
    companion object {
        fun parse(lines: List<String>): Metadata {
            val sources = mutableListOf<String>()
            val cp = mutableListOf<String>()
            lines.forEach { line ->
                when {
                    line.startsWith("S\t") -> sources.add(line.substring(2))
                    line.startsWith("C\t") -> cp.add(line.substring(2))
                }
            }
            return Metadata(sources, cp)
        }

        fun read(file: File): Metadata = parse(file.readLines())
    }
}
