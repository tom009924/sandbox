package com.example.impact

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

private data class MethodId(val owner: String, val name: String, val desc: String) {
    fun shortLabel() = "${owner.replace('/', '.')}.$name()"
}

private data class MethodMeta(
    val id: MethodId,
    val module: String,
    val isTest: Boolean,
    val sourceFile: String?,
    var minLine: Int = Int.MAX_VALUE,
    var maxLine: Int = Int.MIN_VALUE,
    var endpointKind: String? = null,
)

private data class DepArtifactInfo(
    val coord: String, // group:artifact:version
    val jarFile: File,
    val classNames: Set<String>,
    val hasSpringAutoConfig: Boolean,
    val hasSpiServices: Boolean,
)

private val REST_ANNOS = setOf(
    "Lorg/springframework/web/bind/annotation/RequestMapping;",
    "Lorg/springframework/web/bind/annotation/GetMapping;",
    "Lorg/springframework/web/bind/annotation/PostMapping;",
    "Lorg/springframework/web/bind/annotation/PutMapping;",
    "Lorg/springframework/web/bind/annotation/PatchMapping;",
    "Lorg/springframework/web/bind/annotation/DeleteMapping;",
)

private val ASYNC_ANNOS = setOf(
    "Lorg/springframework/scheduling/annotation/Scheduled;",
    "Lorg/springframework/context/event/EventListener;",
)

private val GLOBAL_FALLBACK_PATTERNS = listOf(
    "application.yml", "application.yaml", "application.properties",
    "build.gradle.kts", "libs.versions.toml", "settings.gradle.kts",
    ".proto", ".sql", "docs/openapi.yaml", "docs/asyncapi.yaml",
)

private fun parseGitDiff(rootDir: File, baseRef: String): Triple<Map<String, Set<Int>>, List<String>, List<String>> {
    val proc = ProcessBuilder("git", "-C", rootDir.absolutePath, "diff", "--unified=0", baseRef)
        .redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()

    val changedLinesByFileName = mutableMapOf<String, MutableSet<Int>>()
    val fallbackFiles = mutableListOf<String>()
    val updatedDeps = linkedSetOf<String>()
    var currentPath: String? = null
    val hunkRegex = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")
    val lockEntryRegex = Regex("""^\+([a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[^=\s]+)=.*""")

    for (line in output.lineSequence()) {
        when {
            line.startsWith("+++ b/") -> {
                currentPath = line.removePrefix("+++ b/")
                if (GLOBAL_FALLBACK_PATTERNS.any { currentPath!!.endsWith(it) || currentPath!!.contains(it) }) {
                    fallbackFiles.add(currentPath!!)
                }
            }
            currentPath?.endsWith("gradle.lockfile") == true -> {
                lockEntryRegex.matchEntire(line)?.let { updatedDeps.add(it.groupValues[1]) }
            }
            line.startsWith("@@ ") && currentPath != null -> {
                val m = hunkRegex.find(line) ?: continue
                val start = m.groupValues[1].toInt()
                val count = m.groupValues[2].toIntOrNull() ?: 1
                val set = changedLinesByFileName.computeIfAbsent(File(currentPath!!).name) { mutableSetOf() }
                if (count == 0) set.add(start) else (start until start + count).forEach(set::add)
            }
        }
    }
    return Triple(changedLinesByFileName, fallbackFiles.distinct(), updatedDeps.toList())
}

private fun resolveLockfileCoords(rootDir: File, depQueries: List<String>): List<String> {
    if (depQueries.isEmpty()) return emptyList()
    val allCoords = rootDir.walkTopDown().maxDepth(4)
        .filter { it.isFile && it.name == "gradle.lockfile" }
        .flatMap { file ->
            file.readLines().asSequence()
                .filter { !it.startsWith("#") && '=' in it }
                .map { it.substringBefore('=') }
        }.distinct().toList()

    return depQueries.flatMap { q ->
        if (q.count { it == ':' } == 2) listOf(q)
        else allCoords.filter { it.startsWith("$q:") || it.contains(q) }
    }.distinct()
}

private fun inspectDepJar(coord: String): DepArtifactInfo? {
    val parts = coord.split(':')
    if (parts.size != 3) return null
    val (group, artifact, version) = parts
    val gradleHome = File(System.getenv("GRADLE_USER_HOME") ?: "${System.getProperty("user.home")}/.gradle")
    val versionDir = File(gradleHome, "caches/modules-2/files-2.1/$group/$artifact/$version")
    if (!versionDir.isDirectory) return null
    val jar = versionDir.walkTopDown()
        .firstOrNull { it.isFile && it.name == "$artifact-$version.jar" }
        ?: versionDir.walkTopDown().firstOrNull { it.isFile && it.extension == "jar" && !it.name.endsWith("-sources.jar") }
        ?: return null

    val classes = mutableSetOf<String>()
    var hasAutoConfig = false
    var hasSpi = false
    ZipFile(jar).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            when {
                name.endsWith(".class") && !name.endsWith("module-info.class") && !name.startsWith("META-INF/") ->
                    classes.add(name.removeSuffix(".class"))
                name.startsWith("META-INF/spring/") && name.endsWith(".imports") -> hasAutoConfig = true
                name == "META-INF/spring.factories" -> hasAutoConfig = true
                name.startsWith("META-INF/services/") && !name.endsWith("/") -> hasSpi = true
            }
        }
    }
    return DepArtifactInfo(coord, jar, classes, hasAutoConfig, hasSpi)
}

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
    val gitDiffRef = args.indexOf("--git-diff").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val targetPatterns = args.indices.filter { args[it] == "--target" }.mapNotNull { args.getOrNull(it + 1) }
    val depQueries = args.indices.filter { args[it] == "--dep" }.mapNotNull { args.getOrNull(it + 1) }

    if (gitDiffRef == null && targetPatterns.isEmpty() && depQueries.isEmpty()) {
        println("Usage: impact-analyzer <rootDir> [--git-diff <ref>] [--target <Pattern>]... [--dep <group:artifact[:ver]>]...")
        return
    }

    val seeds = linkedSetOf<MethodId>()
    val fallbackFiles = mutableListOf<String>()
    val changedLines = mutableMapOf<String, Set<Int>>()
    val depCoords = linkedSetOf<String>()

    if (gitDiffRef != null) {
        val (lines, fallbacks, lockUpdates) = parseGitDiff(rootDir, gitDiffRef)
        changedLines.putAll(lines)
        fallbackFiles.addAll(fallbacks)
        depCoords.addAll(lockUpdates)
    }
    depCoords.addAll(resolveLockfileCoords(rootDir, depQueries))

    val inspectedDeps = depCoords.mapNotNull(::inspectDepJar)
    val depClassToCoord = mutableMapOf<String, String>()
    inspectedDeps.forEach { dep -> dep.classNames.forEach { depClassToCoord[it] = dep.coord } }

    val classEntries = listOf("libs", "services").map { File(rootDir, it) }.filter { it.isDirectory }
        .flatMap { g -> g.listFiles()?.filter { it.isDirectory }.orEmpty().map { ":${g.name}:${it.name}" to it } }
        .flatMap { (mod, dir) ->
            listOf(
                "build/classes/kotlin/main" to false,
                "build/classes/java/main" to false,
                "build/classes/kotlin/test" to true,
                "build/classes/java/test" to true,
            ).flatMap { (rel, isTest) ->
                val d = File(dir, rel)
                if (!d.isDirectory) emptyList()
                else d.walkTopDown().filter { it.isFile && it.extension == "class" }.map { Triple(mod, isTest, it) }.toList()
            }
        }

    val methods = linkedMapOf<MethodId, MethodMeta>()
    val methodsByOwner = mutableMapOf<String, MutableList<MethodId>>()
    val callersOf = mutableMapOf<MethodId, MutableSet<MethodId>>()
    val supertypes = mutableMapOf<String, MutableSet<String>>()
    val directDepCallers = mutableMapOf<String, MutableSet<MethodId>>() // depCoord -> calling main methods
    var classCount = 0

    val buildMs = measureTimeMillis {
        for ((mod, isTest, file) in classEntries) {
            classCount++
            ClassReader(file.readBytes()).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    lateinit var cls: String
                    var sourceFile: String? = null
                    var isGrpcImpl = false
                    var isOpenApiImpl = false

                    override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, ifaces: Array<out String>?) {
                        cls = name
                        val supers = listOfNotNull(sup).filter { it != "java/lang/Object" } + ifaces.orEmpty()
                        supers.forEach { supertypes.computeIfAbsent(name) { mutableSetOf() }.add(it) }
                        isGrpcImpl = sup?.endsWith("ImplBase") == true
                        isOpenApiImpl = ifaces.orEmpty().any { it.startsWith("com/example/gateway/presentation/api/") }
                    }

                    override fun visitSource(source: String?, debug: String?) { sourceFile = source }

                    override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                        val id = MethodId(cls, name, desc)
                        val meta = MethodMeta(id, mod, isTest, sourceFile)
                        val isPub = (access and Opcodes.ACC_PUBLIC) != 0
                        val isSyn = (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0
                        if (!isTest && !isSyn && isPub && name !in setOf("<init>", "<clinit>")) {
                            if (isGrpcImpl) meta.endpointKind = "gRPC"
                            else if (isOpenApiImpl) meta.endpointKind = "REST"
                        }
                        methods[id] = meta
                        methodsByOwner.computeIfAbsent(cls) { mutableListOf() }.add(id)

                        fun recordDepUse(internalName: String) {
                            if (isTest) return
                            depClassToCoord[internalName]?.let { coord ->
                                directDepCallers.computeIfAbsent(coord) { mutableSetOf() }.add(id)
                                seeds.add(id)
                            }
                        }

                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(d: String, visible: Boolean): AnnotationVisitor? {
                                if (!isTest) {
                                    if (d in REST_ANNOS) meta.endpointKind = "REST"
                                    if (d in ASYNC_ANNOS) meta.endpointKind = "Scheduled/Event"
                                }
                                return null
                            }

                            override fun visitLineNumber(line: Int, start: Label) {
                                if (line < meta.minLine) meta.minLine = line
                                if (line > meta.maxLine) meta.maxLine = line
                            }

                            override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                                callersOf.computeIfAbsent(MethodId(owner, n, d)) { mutableSetOf() }.add(id)
                                recordDepUse(owner)
                            }

                            override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                                recordDepUse(owner)
                            }

                            override fun visitTypeInsn(op: Int, type: String) {
                                if (!type.startsWith("[")) recordDepUse(type)
                            }

                            override fun visitLdcInsn(value: Any?) {
                                if (value is Type && value.sort == Type.OBJECT) recordDepUse(value.internalName)
                            }

                            override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg bsmArgs: Any?) {
                                for (arg in bsmArgs) {
                                    if (arg is Handle) {
                                        callersOf.computeIfAbsent(MethodId(arg.owner, arg.name, arg.desc)) { mutableSetOf() }.add(id)
                                        recordDepUse(arg.owner)
                                    }
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_FRAMES,
            )
        }

        // Link overriding methods to their supertype/interface methods
        for (m in methods.keys) {
            if (m.name in setOf("<init>", "<clinit>")) continue
            val q = ArrayDeque(supertypes[m.owner].orEmpty())
            val seen = mutableSetOf<String>()
            while (q.isNotEmpty()) {
                val sup = q.removeFirst()
                if (!seen.add(sup)) continue
                callersOf.computeIfAbsent(m) { mutableSetOf() }.add(MethodId(sup, m.name, m.desc))
                q.addAll(supertypes[sup].orEmpty())
            }
        }
    }

    if (changedLines.isNotEmpty()) {
        for (meta in methods.values) {
            if (meta.isTest) continue
            val lines = meta.sourceFile?.let { changedLines[it] } ?: continue
            if (meta.minLine <= meta.maxLine && lines.any { it in meta.minLine..meta.maxLine }) {
                seeds.add(meta.id)
            }
        }
    }

    for (pat in targetPatterns) {
        methods.keys.filter { m -> "${m.owner.replace('/', '.')}.${m.name}".contains(pat) && methods[m]?.isTest == false }
            .forEach(seeds::add)
    }

    // Reverse BFS
    val impactedMethods = linkedSetOf<MethodId>()
    val analysisMs = measureTimeMillis {
        val q = ArrayDeque<MethodId>()
        seeds.forEach { if (impactedMethods.add(it)) q.add(it) }
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (caller in callersOf[cur].orEmpty()) if (impactedMethods.add(caller)) q.add(caller)
            if (cur.name.contains("\$lambda\$")) {
                val hostName = cur.name.substringBefore("\$lambda\$")
                methodsByOwner[cur.owner].orEmpty().filter { it.name == hostName }.forEach {
                    if (impactedMethods.add(it)) q.add(it)
                }
            }
        }
    }

    val impactedEndpoints = impactedMethods.mapNotNull { methods[it] }
        .filter { it.endpointKind != null }
        .sortedWith(compareBy({ it.module }, { it.endpointKind }, { it.id.shortLabel() }))

    val impactedTests = impactedMethods.mapNotNull { methods[it] }
        .filter { it.isTest }
        .map { it.module to it.id.owner.substringBefore('$').replace('/', '.') }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))

    val impactedServices = impactedMethods.mapNotNull { methods[it]?.module }
        .filter { it.startsWith(":services:") }.distinct().sorted()

    println("============================================================")
    println("ASM Impact Analysis Report")
    println("============================================================")
    println("Scanned classes    : $classCount (main + test)")
    println("Analysis time      : ${buildMs + analysisMs} ms (graph=${buildMs}ms, bfs=${analysisMs}ms)")

    if (inspectedDeps.isNotEmpty()) {
        println()
        println("Analyzed third-party dependencies (${inspectedDeps.size}):")
        for (dep in inspectedDeps) {
            val flags = buildList {
                if (dep.hasSpringAutoConfig) add("Spring AutoConfig")
                if (dep.hasSpiServices) add("SPI/Driver")
            }
            val flagStr = if (flags.isNotEmpty()) " [WARNING: ${flags.joinToString(", ")} -> runtime-wide impact possible]" else ""
            val directCount = directDepCallers[dep.coord]?.size ?: 0
            println("  * ${dep.coord} (${dep.classNames.size} classes, $directCount direct caller methods)$flagStr")
            directDepCallers[dep.coord].orEmpty().sortedBy { it.shortLabel() }.take(10).forEach {
                println("      <- [${methods[it]?.module}] ${it.shortLabel()}")
            }
        }
    }

    if (fallbackFiles.isNotEmpty()) {
        println()
        println("[WARNING] Global config/schema files changed -> recommend full test run:")
        fallbackFiles.forEach { println("  ! $it") }
    }

    println()
    println("Impacted services (${impactedServices.size}): ${impactedServices.joinToString(", ").ifEmpty { "(none)" }}")
    println()
    println("Impacted endpoints (${impactedEndpoints.size}):")
    impactedEndpoints.forEach { println("  [${it.module}] (${it.endpointKind}) ${it.id.shortLabel()}") }
    println()
    println("Impacted test classes (${impactedTests.size}):")
    impactedTests.forEach { (mod, cls) -> println("  [$mod] $cls") }
}
