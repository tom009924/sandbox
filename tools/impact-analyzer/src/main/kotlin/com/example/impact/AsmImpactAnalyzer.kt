package com.example.impact

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.system.measureTimeMillis

private data class MethodId(val owner: String, val name: String, val desc: String) {
    val sig get() = name + desc
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

private fun parseGitDiff(rootDir: File, baseRef: String): Pair<Map<String, Set<Int>>, List<String>> {
    val proc = ProcessBuilder("git", "-C", rootDir.absolutePath, "diff", "--unified=0", baseRef)
        .redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()

    val changedLinesByFileName = mutableMapOf<String, MutableSet<Int>>()
    val fallbackFiles = mutableListOf<String>()
    var currentFile: String? = null
    val hunkRegex = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

    for (line in output.lineSequence()) {
        when {
            line.startsWith("+++ b/") -> {
                val path = line.removePrefix("+++ b/")
                currentFile = File(path).name
                if (GLOBAL_FALLBACK_PATTERNS.any { path.endsWith(it) || path.contains(it) }) {
                    fallbackFiles.add(path)
                }
            }
            line.startsWith("@@ ") && currentFile != null -> {
                val m = hunkRegex.find(line) ?: continue
                val start = m.groupValues[1].toInt()
                val count = m.groupValues[2].toIntOrNull() ?: 1
                val set = changedLinesByFileName.computeIfAbsent(currentFile!!) { mutableSetOf() }
                if (count == 0) set.add(start) else (start until start + count).forEach(set::add)
            }
        }
    }
    return changedLinesByFileName to fallbackFiles.distinct()
}

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
    val gitDiffRef = args.indexOf("--git-diff").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val targetPatterns = args.indices.filter { args[it] == "--target" }.mapNotNull { args.getOrNull(it + 1) }

    if (gitDiffRef == null && targetPatterns.isEmpty()) {
        println("Usage: impact-analyzer <rootDir> [--git-diff <ref>] [--target <ClassOrMethodPattern>]...")
        return
    }

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
    val callersOf = mutableMapOf<MethodId, MutableSet<MethodId>>() // callee -> callers
    val supertypes = mutableMapOf<String, MutableSet<String>>()
    val classModule = mutableMapOf<String, String>()
    val testClasses = mutableSetOf<String>()
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
                        classModule[name] = mod
                        if (isTest) testClasses.add(name)
                        val supers = listOfNotNull(sup).filter { it != "java/lang/Object" } + ifaces.orEmpty()
                        supers.forEach { supertypes.computeIfAbsent(name) { mutableSetOf() }.add(it) }
                        isGrpcImpl = sup?.endsWith("ImplBase") == true
                        isOpenApiImpl = ifaces.orEmpty().any { it.startsWith("com/example/gateway/presentation/api/") }
                    }

                    override fun visitSource(source: String?, debug: String?) {
                        sourceFile = source
                    }

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
                            }

                            override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg bsmArgs: Any?) {
                                for (arg in bsmArgs) {
                                    if (arg is Handle) {
                                        callersOf.computeIfAbsent(MethodId(arg.owner, arg.name, arg.desc)) { mutableSetOf() }.add(id)
                                    }
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_FRAMES, // keep LineNumberTable for git-diff line mapping
            )
        }

        // Link overriding methods to their supertype/interface methods so impact flows: impl -> interface -> callers
        for (m in methods.keys) {
            if (m.name in setOf("<init>", "<clinit>")) continue
            val q = ArrayDeque(supertypes[m.owner].orEmpty())
            val seen = mutableSetOf<String>()
            while (q.isNotEmpty()) {
                val sup = q.removeFirst()
                if (!seen.add(sup)) continue
                val supMethod = MethodId(sup, m.name, m.desc)
                callersOf.computeIfAbsent(m) { mutableSetOf() }.add(supMethod)
                q.addAll(supertypes[sup].orEmpty())
            }
        }
    }

    val seeds = linkedSetOf<MethodId>()
    val fallbackFiles = mutableListOf<String>()

    if (gitDiffRef != null) {
        val (changedLines, fallbacks) = parseGitDiff(rootDir, gitDiffRef)
        fallbackFiles.addAll(fallbacks)
        for (meta in methods.values) {
            if (meta.isTest) continue
            val lines = meta.sourceFile?.let { changedLines[it] } ?: continue
            if (meta.minLine <= meta.maxLine && lines.any { it in meta.minLine..meta.maxLine }) {
                seeds.add(meta.id)
            }
        }
    }

    for (pat in targetPatterns) {
        val matched = methods.keys.filter { m ->
            val fqn = "${m.owner.replace('/', '.')}.${m.name}"
            fqn.contains(pat) && methods[m]?.isTest == false
        }
        seeds.addAll(matched)
    }

    // Reverse BFS from changed methods -> callers
    val impactedMethods = linkedSetOf<MethodId>()
    val analysisMs = measureTimeMillis {
        val q = ArrayDeque<MethodId>()
        seeds.forEach { if (impactedMethods.add(it)) q.add(it) }
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (caller in callersOf[cur].orEmpty()) {
                if (impactedMethods.add(caller)) q.add(caller)
            }
            // If a synthetic lambda / inner class method is impacted, propagate to enclosing class methods if any
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
        .filter { it.startsWith(":services:") }
        .distinct().sorted()

    println("============================================================")
    println("ASM Impact Analysis Report")
    println("============================================================")
    println("Scanned classes    : $classCount (main + test)")
    println("Analysis time      : ${buildMs + analysisMs} ms (graph=${buildMs}ms, bfs=${analysisMs}ms)")
    println("Changed seed methods (${seeds.size}):")
    seeds.take(15).forEach { println("  * ${it.shortLabel()}") }
    if (seeds.size > 15) println("  ... and ${seeds.size - 15} more")

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
