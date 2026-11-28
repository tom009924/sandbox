package com.example.deadcode.asm

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.File
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private data class MethodId(val owner: String, val name: String, val desc: String) {
    override fun toString() = "${owner.replace('/', '.')}.$name$desc"
}

// Always-alive Spring entry points (invoked directly by the framework / HTTP / scheduler / etc.)
private val ENTRY_CLASS_ANNOS = setOf(
    "Lorg/springframework/boot/autoconfigure/SpringBootApplication;",
    "Lorg/springframework/stereotype/Controller;",
    "Lorg/springframework/web/bind/annotation/RestController;",
    "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
    "Lorg/springframework/web/bind/annotation/RestControllerAdvice;",
    "Lorg/springframework/context/annotation/Configuration;",
    "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;",
)

// DI-managed beans: alive only if injected (directly or via supertype/interface) by another class
private val DI_BEAN_ANNOS = setOf(
    "Lorg/springframework/stereotype/Component;",
    "Lorg/springframework/stereotype/Service;",
    "Lorg/springframework/stereotype/Repository;",
    "Lorg/springframework/boot/context/properties/ConfigurationProperties;",
)

private val ENTRY_METHOD_ANNOS = setOf(
    "Lorg/springframework/web/bind/annotation/RequestMapping;",
    "Lorg/springframework/web/bind/annotation/GetMapping;",
    "Lorg/springframework/web/bind/annotation/PostMapping;",
    "Lorg/springframework/web/bind/annotation/PutMapping;",
    "Lorg/springframework/web/bind/annotation/PatchMapping;",
    "Lorg/springframework/web/bind/annotation/DeleteMapping;",
    "Lorg/springframework/web/bind/annotation/ExceptionHandler;",
    "Lorg/springframework/scheduling/annotation/Scheduled;",
    "Lorg/springframework/context/event/EventListener;",
    "Ljakarta/annotation/PostConstruct;",
    "Ljakarta/annotation/PreDestroy;",
)

private const val BEAN_METHOD_ANNO = "Lorg/springframework/context/annotation/Bean;"

private val GENERATED_PREFIXES = listOf(
    "com/example/identity/proto/",
    "com/example/todo/proto/",
    "com/example/gateway/api/",
    "com/example/gateway/presentation/api/",
    "com/example/gateway/presentation/model/",
    "com/example/todo/domain/decision/generated/",
)

private fun addTypes(desc: String, out: MutableSet<String>) {
    fun visit(t: Type): Unit = when (t.sort) {
        Type.OBJECT -> { out.add(t.internalName); Unit }
        Type.ARRAY -> visit(t.elementType)
        Type.METHOD -> { t.argumentTypes.forEach(::visit); visit(t.returnType) }
        else -> Unit
    }
    runCatching { visit(Type.getType(desc)) }
}

private fun addSignatureTypes(signature: String?, out: MutableSet<String>) {
    if (signature == null) return
    runCatching {
        SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) { out.add(name) }
        })
    }
}

private fun annoValueVisitor(out: MutableSet<String>): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) { if (value is Type) addTypes(value.descriptor, out) }
    override fun visitArray(name: String?): AnnotationVisitor = this
    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        addTypes(descriptor, out); return this
    }
}

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
    val baselinePath = args.indexOf("--baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val updateBaselinePath = args.indexOf("--update-baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val classFiles = listOf("libs", "services").map { File(rootDir, it) }.filter { it.isDirectory }
        .flatMap { g -> g.listFiles()?.filter { it.isDirectory }.orEmpty().map { ":${g.name}:${it.name}" to it } }
        .flatMap { (mod, dir) ->
            listOf("build/classes/kotlin/main", "build/classes/java/main").map { File(dir, it) }.filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "class" }.map { f -> mod to f }.toList() }
        }

    val classModules = linkedMapOf<String, String>()
    val declaredMethods = linkedMapOf<MethodId, String>()
    val referencedClasses = mutableSetOf<String>()
    val calledMethods = mutableSetOf<MethodId>()
    val calledSigsByOwner = mutableMapOf<String, MutableSet<String>>()
    val subtypes = mutableMapOf<String, MutableSet<String>>()
    val supertypes = mutableMapOf<String, MutableSet<String>>()
    val diBeanClasses = mutableSetOf<String>()
    val beanFactoryMethods = mutableMapOf<MethodId, String?>() // @Bean method -> return type internal name
    val skippedClasses = mutableSetOf<String>()

    val elapsedMs = measureTimeMillis {
        for ((mod, file) in classFiles) {
            ClassReader(file.readBytes()).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    lateinit var cls: String
                    var isEnum = false
                    var isDataClass = false
                    var isTable = false
                    var isFrameworkSub = false
                    val localMethods = mutableListOf<Pair<MethodId, Int>>()
                    val entryMethods = mutableSetOf<MethodId>()

                    override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, ifaces: Array<out String>?) {
                        cls = name
                        isEnum = (access and Opcodes.ACC_ENUM) != 0
                        classModules[name] = mod
                        val supers = listOfNotNull(sup).filter { it != "java/lang/Object" } + ifaces.orEmpty()
                        supers.forEach { s ->
                            subtypes.computeIfAbsent(s) { mutableSetOf() }.add(name)
                            supertypes.computeIfAbsent(name) { mutableSetOf() }.add(s)
                        }
                        isFrameworkSub = supers.any { s ->
                            s.endsWith("ImplBase") || s == "java/lang/AutoCloseable" || s == "java/io/Closeable" ||
                                listOf("org/springframework/", "io/grpc/", "io/opentelemetry/", "com/google/cloud/spring/", "com/example/gateway/presentation/api/")
                                    .any { s.startsWith(it) }
                        }
                        if (isFrameworkSub) referencedClasses.add(name)
                        if (mod.endsWith("-proto") || GENERATED_PREFIXES.any(name::startsWith) ||
                            name.contains("\$inlined\$") || name.contains("\$WhenMappings") || name.endsWith("\$DefaultImpls") ||
                            (name.contains('$') && name.substringAfterLast('$').all(Char::isDigit))
                        ) skippedClasses.add(name)
                    }

                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
                        addTypes(desc, referencedClasses)
                        if (desc in ENTRY_CLASS_ANNOS) referencedClasses.add(cls)
                        if (desc in DI_BEAN_ANNOS) diBeanClasses.add(cls)
                        if (desc == "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;") isTable = true
                        return annoValueVisitor(referencedClasses)
                    }

                    override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                        addTypes(desc, referencedClasses); addSignatureTypes(sig, referencedClasses); return null
                    }

                    override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                        val id = MethodId(cls, name, desc)
                        if (name == "main") referencedClasses.add(cls)
                        if (name == "copy") isDataClass = true
                        localMethods.add(id to access)
                        val argTypes = mutableSetOf<String>()
                        val retType = runCatching { Type.getReturnType(desc) }.getOrNull()
                        runCatching { Type.getArgumentTypes(desc).forEach { addTypes(it.descriptor, argTypes) } }
                        ex?.forEach(argTypes::add)
                        var isBeanMethod = false

                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(d: String, v: Boolean): AnnotationVisitor {
                                addTypes(d, referencedClasses)
                                if (d in ENTRY_METHOD_ANNOS) {
                                    entryMethods.add(id); referencedClasses.add(cls)
                                }
                                if (d == BEAN_METHOD_ANNO) isBeanMethod = true
                                return annoValueVisitor(referencedClasses)
                            }
                            override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                                calledMethods.add(MethodId(owner, n, d))
                                calledSigsByOwner.computeIfAbsent(owner) { mutableSetOf() }.add(n + d)
                                if (owner != cls) referencedClasses.add(owner)
                                addTypes(d, referencedClasses)
                            }
                            override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                                if (owner != cls) referencedClasses.add(owner)
                                addTypes(d, referencedClasses)
                            }
                            override fun visitTypeInsn(op: Int, type: String) {
                                val t = if (type.startsWith("[")) null else type
                                if (t != null) { if (t != cls) referencedClasses.add(t) } else addTypes(type, referencedClasses)
                            }
                            override fun visitLdcInsn(value: Any?) {
                                if (value is Type) addTypes(value.descriptor, referencedClasses)
                            }
                            override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg bsmArgs: Any?) {
                                addTypes(d, referencedClasses)
                                for (arg in bsmArgs) when (arg) {
                                    is Handle -> {
                                        calledMethods.add(MethodId(arg.owner, arg.name, arg.desc))
                                        calledSigsByOwner.computeIfAbsent(arg.owner) { mutableSetOf() }.add(arg.name + arg.desc)
                                        if (arg.owner != cls) referencedClasses.add(arg.owner)
                                    }
                                    is Type -> addTypes(arg.descriptor, referencedClasses)
                                }
                            }
                            override fun visitEnd() {
                                referencedClasses.addAll(argTypes)
                                if (isBeanMethod) {
                                    // Don't self-mark the returned type as referenced; check if anyone injects it
                                    val retInternal = retType?.takeIf { it.sort == Type.OBJECT }?.internalName
                                    beanFactoryMethods[id] = retInternal
                                } else {
                                    retType?.let { addTypes(it.descriptor, referencedClasses) }
                                    addSignatureTypes(sig, referencedClasses)
                                }
                            }
                        }
                    }

                    override fun visitEnd() {
                        if (cls.endsWith("Kt") && localMethods.none { (m, a) -> (a and Opcodes.ACC_SYNTHETIC) == 0 && m.name != "<clinit>" }) {
                            skippedClasses.add(cls)
                        }
                        if (cls in skippedClasses || isFrameworkSub) return
                        for ((m, access) in localMethods) {
                            val isSyn = (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_ABSTRACT)) != 0
                            if (isSyn || m in entryMethods) continue
                            if (m.name in setOf("<init>", "<clinit>", "main", "copy", "toString", "hashCode", "equals")) continue
                            if (m.name.contains("\$lambda\$") || m.name.endsWith("\$default")) continue
                            if (m.name.startsWith("component") && m.name.removePrefix("component").all(Char::isDigit)) continue
                            if (isEnum && m.name in setOf("values", "valueOf", "getEntries")) continue
                            if (isDataClass && (m.name.startsWith("get") || m.name.startsWith("is"))) continue
                            if (isTable && (m.name.startsWith("get") || m.name.startsWith("set"))) continue
                            declaredMethods[m] = mod
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }

        // Resolve DI beans: if a supertype/interface is referenced (injected), mark implementing DI beans as referenced
        val q = ArrayDeque(referencedClasses)
        val seenSub = mutableSetOf<String>()
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (sub in subtypes[cur].orEmpty()) {
                if (sub in diBeanClasses && seenSub.add(sub)) {
                    referencedClasses.add(sub)
                    q.add(sub)
                }
            }
        }

        // Resolve @Bean factory methods: alive if returning an external/framework type or a referenced local type
        for ((beanMethod, retInternal) in beanFactoryMethods) {
            val isExternal = retInternal == null || retInternal !in classModules
            val isUsed = isExternal || retInternal in referencedClasses ||
                supertypes[retInternal].orEmpty().any { it !in classModules || it in referencedClasses }
            if (isUsed) calledMethods.add(beanMethod)
        }

        // Propagate calls on supertypes to overriding subtype methods
        for ((owner, sigs) in calledSigsByOwner) {
            val subQ = ArrayDeque(subtypes[owner].orEmpty())
            val seen = mutableSetOf<String>()
            while (subQ.isNotEmpty()) {
                val sub = subQ.removeFirst()
                if (!seen.add(sub)) continue
                sigs.forEach { sig -> calledMethods.add(MethodId(sub, sig.substringBefore('('), "(" + sig.substringAfter('('))) }
                subQ.addAll(subtypes[sub].orEmpty())
            }
        }
    }

    val deadClasses = classModules.filterKeys { it !in skippedClasses && it !in referencedClasses && !it.endsWith("\$Companion") }
    val deadMethods = declaredMethods.filterKeys { it !in calledMethods && it.owner !in deadClasses }

    val findings = buildList {
        deadClasses.entries.sortedWith(compareBy({ it.value }, { it.key })).forEach { (c, m) ->
            val tag = if (c in diBeanClasses) " [unused bean]" else ""
            add("class:[$m] ${c.replace('/', '.')}$tag")
        }
        deadMethods.entries.sortedWith(compareBy({ it.value }, { it.key.toString() })).forEach { (k, m) ->
            val tag = if (k in beanFactoryMethods) " [unused @Bean]" else ""
            add("method:[$m] $k$tag")
        }
    }

    println("ASM Dead Code Report (src/main only, Spring Bean-aware): ${classFiles.size} classes | ${elapsedMs} ms")
    println("Total findings: ${findings.size} (${deadClasses.size} classes/beans, ${deadMethods.size} methods)")

    if (updateBaselinePath != null) {
        val out = File(updateBaselinePath)
        out.parentFile?.mkdirs()
        val header = "# Production dead-code baseline (ASM analyzer). Regenerate with --update-baseline.\n"
        out.writeText(header + findings.joinToString("\n") + if (findings.isNotEmpty()) "\n" else "")
        println("Updated baseline (${findings.size} entries) at ${out.path}")
        return
    }

    if (baselinePath != null) {
        val baselineFile = File(baselinePath)
        val baseline = if (baselineFile.exists()) {
            baselineFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        } else {
            emptySet()
        }
        val newFindings = findings.filterNot { it in baseline }
        val staleEntries = baseline.filterNot { it in findings.toSet() }.sorted()
        if (staleEntries.isNotEmpty()) {
            println("Stale baseline entries (${staleEntries.size} - can be removed):")
            staleEntries.forEach { println("  - $it") }
        }
        if (newFindings.isNotEmpty()) {
            println("NEW production dead code detected (${newFindings.size}):")
            newFindings.forEach { println("  + $it") }
            exitProcess(1)
        }
        println("OK: No new production dead code beyond baseline (${baseline.size} baselined).")
    } else {
        findings.forEach { println("  $it") }
    }
}
