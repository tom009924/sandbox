import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File

fun escapeJson(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}

fun jsonStringArray(items: Collection<String>): String =
    items.joinToString(prefix = "[", postfix = "]", separator = ", ") { escapeJson(it) }

gradle.projectsEvaluated {
    val root = gradle.rootProject
    val targetProjects = root.subprojects
        .filter { it.path.startsWith(":libs:") || it.path.startsWith(":services:") }
        .sortedBy { it.path }

    root.tasks.register("exportDeadCodeMetadata") {
        group = "verification"
        description = "Exports main source roots, classes dirs, and compile classpaths for dead-code analysis."

        targetProjects.forEach { sub ->
            sub.tasks.findByName("classes")?.let { dependsOn(it) }
        }

        doLast {
            val outFile = File(root.projectDir, "tools/dead-code-analyzer/build/sandbox-metadata.json")
            outFile.parentFile.mkdirs()

            val moduleEntries = targetProjects.mapNotNull { sub ->
                val javaExt = sub.extensions.findByType(JavaPluginExtension::class.java) ?: return@mapNotNull null
                val mainSourceSet = javaExt.sourceSets.findByName("main") ?: return@mapNotNull null

                val buildDirPath = sub.layout.buildDirectory.get().asFile.canonicalPath
                val allSrcDirs = mainSourceSet.allSource.srcDirs
                    .filter { it.exists() && it.isDirectory }
                    .map { it.canonicalPath }
                    .distinct()

                val generatedSrcDirs = allSrcDirs.filter { it.startsWith(buildDirPath) }
                val handWrittenSrcDirs = allSrcDirs.filterNot { it.startsWith(buildDirPath) }

                val classesDirs = mainSourceSet.output.classesDirs.files
                    .filter { it.exists() }
                    .map { it.canonicalPath }
                    .distinct()

                val compileClasspath = mainSourceSet.compileClasspath.files
                    .filter { it.exists() }
                    .map { it.canonicalPath }
                    .distinct()

                val projectDeps = listOf("implementation", "api", "compileOnly")
                    .mapNotNull { sub.configurations.findByName(it) }
                    .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                    .map { it.path }
                    .distinct()
                    .sorted()

                """
                |    {
                |      "path": ${escapeJson(sub.path)},
                |      "projectDir": ${escapeJson(sub.projectDir.canonicalPath)},
                |      "sourceRoots": ${jsonStringArray(handWrittenSrcDirs)},
                |      "generatedSourceRoots": ${jsonStringArray(generatedSrcDirs)},
                |      "classesDirs": ${jsonStringArray(classesDirs)},
                |      "compileClasspath": ${jsonStringArray(compileClasspath)},
                |      "projectDependencies": ${jsonStringArray(projectDeps)}
                |    }
                """.trimMargin()
            }

            val json = "{\n  \"modules\": [\n${moduleEntries.joinToString(",\n")}\n  ]\n}\n"
            outFile.writeText(json)
            logger.lifecycle("Exported dead-code metadata for ${moduleEntries.size} modules to ${outFile.canonicalPath}")
        }
    }
}
