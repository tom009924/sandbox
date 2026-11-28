package com.example.deadcode.analysisapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AnalysisApiDeadCodeAnalyzerTest {
    private val analyzer = AnalysisApiDeadCodeAnalyzer()

    private fun writeSpringStubs(dir: File) {
        val genDir = File(dir, "generated").apply { mkdirs() }
        File(genDir, "SpringStubs.kt").writeText(
            """
            package com.example.stubs
            annotation class RestController
            annotation class GetMapping
            annotation class Service
            annotation class Repository
            annotation class Component
            annotation class Configuration
            annotation class Bean
            """.trimIndent(),
        )
    }

    private fun analyzeSources(dir: File, vararg files: Pair<String, String>): AnalysisReport {
        writeSpringStubs(dir)
        files.forEach { (name, content) -> File(dir, name).writeText(content.trimIndent()) }
        return analyzer.analyze(Metadata(sourceRoots = listOf(dir.absolutePath), classpath = emptyList()))
    }

    @Test
    fun `detects unreferenced function and ignores called function`(@TempDir dir: File) {
        val report = analyzeSources(
            dir,
            "App.kt" to """
                package com.example
                fun main() { Helper().used() }
                class Helper {
                    fun used() {}
                    fun unused() {}
                }
            """,
        )

        assertEquals(emptyList<String>(), report.deadClasses.map { it.key })
        assertEquals(listOf("member:com.example.Helper.unused()"), report.deadMembers.map { it.key })
    }

    @Test
    fun `detects uninjected Spring Service bean and keeps interface-injected Repository`(@TempDir dir: File) {
        val report = analyzeSources(
            dir,
            "Sample.kt" to """
                package com.example
                import com.example.stubs.*

                @RestController
                class MyController(private val repo: TodoRepo) {
                    @GetMapping
                    fun handle() { repo.save() }
                }

                interface TodoRepo {
                    fun save()
                    fun unusedQuery()
                }

                @Repository
                class TodoRepoImpl : TodoRepo {
                    override fun save() {}
                    override fun unusedQuery() {}
                }

                @Service
                class DeadService {
                    fun neverCalled() {}
                }
            """,
        )

        assertEquals(
            listOf("class:com.example.DeadService [unused bean]"),
            report.deadClasses.map { it.key },
        )
        assertEquals(
            listOf(
                "member:com.example.TodoRepo.unusedQuery()",
                "member:com.example.TodoRepoImpl.unusedQuery()",
            ),
            report.deadMembers.map { it.key },
        )
    }

    @Test
    fun `detects unused Bean returning local type and keeps Bean returning external type`(@TempDir dir: File) {
        val report = analyzeSources(
            dir,
            "Config.kt" to """
                package com.example
                import com.example.stubs.*

                interface UnusedLocalEncoder {
                    fun encode(): String
                }

                @Configuration
                class AppConfig {
                    @Bean
                    fun unusedEncoder(): UnusedLocalEncoder = object : UnusedLocalEncoder {
                        override fun encode(): String = ""
                    }

                    @Bean
                    fun externalStringBean(): String = "ok"
                }
            """,
        )

        assertEquals(listOf("class:com.example.UnusedLocalEncoder"), report.deadClasses.map { it.key })
        assertTrue(
            "member:com.example.AppConfig.unusedEncoder() [unused @Bean]" in report.deadMembers.map { it.key },
            "Expected unused @Bean in ${report.deadMembers}",
        )
        assertTrue(
            report.deadMembers.none { it.key.contains("externalStringBean") },
            "External @Bean should not be reported: ${report.deadMembers}",
        )
    }

    @Test
    fun `does not count import statements as usages and ignores data class properties`(@TempDir dir: File) {
        val report = analyzeSources(
            dir,
            "Models.kt" to """
                package com.example.model
                data class UsedDto(val id: String)
                class OnlyImported
            """,
            "Entry.kt" to """
                package com.example.app
                import com.example.model.UsedDto
                import com.example.model.OnlyImported

                fun main() {
                    val d = UsedDto("1")
                    d.toString()
                }
            """,
        )

        assertEquals(listOf("class:com.example.model.OnlyImported"), report.deadClasses.map { it.key })
        assertEquals(emptyList<String>(), report.deadMembers.map { it.key })
    }
}
