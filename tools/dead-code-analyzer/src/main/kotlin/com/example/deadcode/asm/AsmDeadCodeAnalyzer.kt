package com.example.deadcode.asm

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import kotlin.system.measureTimeMillis

private data class MethodId(val owner: String, val name: String, val desc: String) {
    val sig get() = name + desc
    override fun toString() = "${owner.replace('/', '.')}.$name$desc"
}

private val FRAMEWORK_ANNOS = setOf(
    "Lorg/springframework/boot/autoconfigure/SpringBootApplication;",
    "Lorg/springframework/stereotype/Component;",
    "Lorg/springframework/stereotype/Service;",
    "Lorg/springframework/stereotype/Repository;",
    "Lorg/springframework/stereotype/Controller;",
    "Lorg/springframework/web/bind/annotation/RestController;",
    "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
    "Lorg/springframework/web/bind/annotation/RestControllerAdvice;",
    "Lorg/springframework/context/annotation/Configuration;",
    "Lorg/springframework/boot/context/properties/ConfigurationProperties;",
    "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;",
    "Lorg/springframework/context/annotation/Bean;",
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

private val GENERATED_PREFIXES = listOf(
    "com/example/identity/proto/",
    "com/example/todo/proto/",
    "com/example/gateway/api/",
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

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull() ?: ".")
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
                    val annotatedMethods = mutableSetOf<MethodId>()

                    override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, ifaces: Array<out String>?) {
                        cls = name
                        isEnum = (access and Opcodes.ACC_ENUM) != 0
                        classModules[name] = mod
                        val supers = listOfNotNull(sup) + ifaces.orEmpty()
                        supers.forEach { s ->
                            referencedClasses.add(s)
                            subtypes.computeIfAbsent(s) { mutableSetOf() }.add(name)
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

                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                        addTypes(desc, referencedClasses)
                        if (desc in FRAMEWORK_ANNOS) referencedClasses.add(cls)
                        if (desc == "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;") isTable = true
                        return null
                    }

                    override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                        addTypes(desc, referencedClasses); return null
                    }

                    override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                        val id = MethodId(cls, name, desc)
                        if (name == "main") referencedClasses.add(cls)
                        if (name == "copy") isDataClass = true
                        localMethods.add(id to access)
                        addTypes(desc, referencedClasses); ex?.forEach(referencedClasses::add)
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(d: String, v: Boolean): AnnotationVisitor? {
                                addTypes(d, referencedClasses)
                                if (d in FRAMEWORK_ANNOS) annotatedMethods.add(id)
                                return null
                            }
                            override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                                calledMethods.add(MethodId(owner, n, d))
                                calledSigsByOwner.computeIfAbsent(owner) { mutableSetOf() }.add(n + d)
                                referencedClasses.add(owner); addTypes(d, referencedClasses)
                            }
                            override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                                referencedClasses.add(owner); addTypes(d, referencedClasses)
                            }
                            override fun visitTypeInsn(op: Int, type: String) {
                                if (type.startsWith("[")) addTypes(type, referencedClasses) else referencedClasses.add(type)
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
                                        referencedClasses.add(arg.owner)
                                    }
                                    is Type -> addTypes(arg.descriptor, referencedClasses)
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
                            if (isSyn || m in annotatedMethods) continue
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

        // Propagate calls on supertypes to overriding subtype methods
        for ((owner, sigs) in calledSigsByOwner) {
            val q = ArrayDeque(subtypes[owner].orEmpty())
            val seen = mutableSetOf<String>()
            while (q.isNotEmpty()) {
                val sub = q.removeFirst()
                if (!seen.add(sub)) continue
                sigs.forEach { sig -> calledMethods.add(MethodId(sub, sig.substringBefore('('), "(" + sig.substringAfter('('))) }
                q.addAll(subtypes[sub].orEmpty())
            }
        }
    }

    val deadClasses = classModules.filterKeys { it !in skippedClasses && it !in referencedClasses && !it.endsWith("\$Companion") }
    val deadMethods = declaredMethods.filterKeys { it !in calledMethods && it.owner !in deadClasses }

    println("ASM Dead Code Report (src/main only): ${classFiles.size} classes | ${elapsedMs} ms")
    println("Unreferenced classes (${deadClasses.size}):")
    deadClasses.entries.sortedWith(compareBy({ it.value }, { it.key })).forEach { (c, m) -> println("  [$m] ${c.replace('/', '.')}") }
    println("Unreferenced methods (${deadMethods.size}):")
    deadMethods.entries.sortedWith(compareBy({ it.value }, { it.key.toString() })).forEach { (k, m) -> println("  [$m] $k") }
}
