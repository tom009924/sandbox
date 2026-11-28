import groovy.json.JsonOutput
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File

gradle.projectsEvaluated {
    val root = gradle.rootProject
    val targets = root.subprojects.filter { it.path.startsWith(":libs:") || it.path.startsWith(":services:") }

    root.tasks.register("exportDeadCodeMetadata") {
        targets.mapNotNull { it.tasks.findByName("classes") }.forEach(::dependsOn)
        doLast {
            val sourceRoots = linkedSetOf<String>()
            val classpath = linkedSetOf<String>()
            val internalDirs = targets.filterNot { it.path.endsWith("-proto") }
                .mapNotNull { it.extensions.findByType(JavaPluginExtension::class.java) }
                .flatMap { it.sourceSets.findByName("main")?.output?.classesDirs?.files.orEmpty() }
                .map { it.canonicalPath }.toSet()

            for (sub in targets) {
                val main = sub.extensions.findByType(JavaPluginExtension::class.java)?.sourceSets?.findByName("main") ?: continue
                if (!sub.path.endsWith("-proto")) {
                    main.allSource.srcDirs.filter { it.isDirectory }.mapTo(sourceRoots) { it.canonicalPath }
                }
                main.compileClasspath.files
                    .filter { it.exists() && it.canonicalPath !in internalDirs }
                    .mapTo(classpath) { it.canonicalPath }
            }

            val out = File(root.projectDir, "tools/dead-code-analyzer/build/sandbox-metadata.json")
            out.parentFile.mkdirs()
            out.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("sourceRoots" to sourceRoots, "classpath" to classpath))))
            logger.lifecycle("Exported ${sourceRoots.size} source roots and ${classpath.size} classpath entries to ${out.canonicalPath}")
        }
    }
}
