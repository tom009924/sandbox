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

private data class MethodKey(val owner: String, val name: String, val desc: String) {
    val sig: String get() = name + desc
    override fun toString() = "${owner.replace('/', '.')}.$name$desc"
}

private data class MethodNode(
    val key: MethodKey,
    val access: Int,
    val annotations: MutableSet<String> = mutableSetOf(),
    val calls: MutableSet<MethodKey> = mutableSetOf(),
    val types: MutableSet<String> = mutableSetOf(),
) {
    val isSynthetic get() = (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0
    val isAbstract get() = (access and Opcodes.ACC_ABSTRACT) != 0
    val isStatic get() = (access and Opcodes.ACC_STATIC) != 0
}

private data class ClassNode(
    val module: String,
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val annotations: MutableSet<String> = mutableSetOf(),
    val methods: MutableMap<String, MethodNode> = mutableMapOf(),
    val types: MutableSet<String> = mutableSetOf(),
) {
    val isEnum get() = (access and Opcodes.ACC_ENUM) != 0
}

private val ENTRY_CLASS_ANNOS = setOf(
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
)

private val ENTRY_METHOD_ANNOS = setOf(
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
    "Lcom/fasterxml/jackson/annotation/JsonCreator;",
    "Lcom/fasterxml/jackson/annotation/JsonValue;",
)

private val FRAMEWORK_PREFIXES = listOf(
    "org/springframework/",
    "jakarta/servlet/",
    "io/grpc/",
    "io/opentelemetry/",
    "com/google/cloud/spring/",
    "com/example/gateway/api/",
)

private val GENERATED_PREFIXES = listOf(
    "com/example/identity/proto/",
    "com/example/todo/proto/",
    "com/example/gateway/api/",
    "com/example/todo/domain/decision/generated/",
)

private val OBJECT_METHODS = setOf("toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z")

private fun collectTypes(descriptor: String, out: MutableSet<String>) {
    fun add(t: Type): Unit = when (t.sort) {
        Type.OBJECT -> { out.add(t.internalName); Unit }
        Type.ARRAY -> add(t.elementType)
        Type.METHOD -> { t.argumentTypes.forEach(::add); add(t.returnType) }
        else -> Unit
    }
    runCatching { add(Type.getType(descriptor)) }
}

private fun bfsHierarchy(start: String, adj: Map<String, Set<String>>): Set<String> {
    val visited = mutableSetOf<String>()
    val q = ArrayDeque(listOf(start))
    while (q.isNotEmpty()) {
        for (next in adj[q.removeFirst()].orEmpty()) if (visited.add(next)) q.add(next)
    }
    return visited
}

fun main(args: Array<String>) {
    val rootDir = File(args.firstOrNull() ?: ".")
    val moduleDirs = listOf("libs", "services")
        .map { File(rootDir, it) }
        .filter { it.isDirectory }
        .flatMap { group -> group.listFiles()?.filter { it.isDirectory }.orEmpty().map { ":${group.name}:${it.name}" to it } }
        .sortedBy { it.first }

    val classes = linkedMapOf<String, ClassNode>()
    var classFileCount = 0

    val parseMs = measureTimeMillis {
        for ((modulePath, modDir) in moduleDirs) {
            listOf("build/classes/kotlin/main", "build/classes/java/main")
                .map { File(modDir, it) }
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "class" }.toList() }
                .forEach { file ->
                    classFileCount++
                    ClassReader(file.readBytes()).accept(
                        object : ClassVisitor(Opcodes.ASM9) {
                            lateinit var cls: ClassNode

                            override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, ifaces: Array<out String>?) {
                                cls = ClassNode(modulePath, name, sup, ifaces?.toList().orEmpty(), access)
                                sup?.let(cls.types::add)
                                cls.interfaces.forEach(cls.types::add)
                                classes[name] = cls
                            }

                            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                                cls.annotations.add(desc); collectTypes(desc, cls.types); return null
                            }

                            override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                                collectTypes(desc, cls.types); return null
                            }

                            override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                                val m = MethodNode(MethodKey(cls.name, name, desc), access)
                                collectTypes(desc, m.types); ex?.forEach(m.types::add)
                                cls.methods[m.key.sig] = m
                                return object : MethodVisitor(Opcodes.ASM9) {
                                    override fun visitAnnotation(d: String, v: Boolean): AnnotationVisitor? {
                                        m.annotations.add(d); collectTypes(d, m.types); return null
                                    }
                                    override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                                        m.calls.add(MethodKey(owner, n, d)); m.types.add(owner); collectTypes(d, m.types)
                                    }
                                    override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                                        m.types.add(owner); collectTypes(d, m.types)
                                    }
                                    override fun visitTypeInsn(op: Int, type: String) {
                                        if (type.startsWith("[")) collectTypes(type, m.types) else m.types.add(type)
                                    }
                                    override fun visitLdcInsn(value: Any?) {
                                        if (value is Type) collectTypes(value.descriptor, m.types)
                                    }
                                    override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                                        collectTypes(d, m.types)
                                        for (arg in args) when (arg) {
                                            is Handle -> { m.calls.add(MethodKey(arg.owner, arg.name, arg.desc)); m.types.add(arg.owner) }
                                            is Type -> collectTypes(arg.descriptor, m.types)
                                        }
                                    }
                                }
                            }
                        },
                        ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                    )
                }
        }
    }

    val subtypes = mutableMapOf<String, MutableSet<String>>()
    val supertypes = mutableMapOf<String, MutableSet<String>>()
    for (c in classes.values) {
        (listOfNotNull(c.superName) + c.interfaces).forEach { sup ->
            subtypes.computeIfAbsent(sup) { mutableSetOf() }.add(c.name)
            supertypes.computeIfAbsent(c.name) { mutableSetOf() }.add(sup)
        }
    }

    val reachableClasses = mutableSetOf<String>()
    val reachableMethods = mutableSetOf<MethodKey>()
    val classQ = ArrayDeque<String>()
    val methodQ = ArrayDeque<MethodKey>()

    fun markClass(name: String) {
        val c = classes[name] ?: return
        if (!reachableClasses.add(name)) return
        classQ.add(name)
        c.methods["<clinit>()V"]?.let { if (reachableMethods.add(it.key)) methodQ.add(it.key) }
        name.lastIndexOf('$').takeIf { it > 0 }?.let { markClass(name.substring(0, it)) }
        c.superName?.let(::markClass)
        c.interfaces.forEach(::markClass)
    }

    fun markMethod(key: MethodKey) {
        val c = classes[key.owner] ?: return
        markClass(key.owner)
        val direct = c.methods[key.sig]
        if (direct != null) {
            if (reachableMethods.add(direct.key)) methodQ.add(direct.key)
        } else {
            for (sup in bfsHierarchy(key.owner, supertypes)) {
                classes[sup]?.methods?.get(key.sig)?.let {
                    markClass(sup); if (reachableMethods.add(it.key)) methodQ.add(it.key)
                }
            }
        }
        if (key.name !in setOf("<init>", "<clinit>")) {
            for (sub in bfsHierarchy(key.owner, subtypes)) {
                classes[sub]?.methods?.get(key.sig)?.let {
                    markClass(sub); if (reachableMethods.add(it.key)) methodQ.add(it.key)
                }
            }
        }
    }

    val analysisMs = measureTimeMillis {
        for (c in classes.values) {
            val externalSupers = (listOfNotNull(c.superName) + c.interfaces).filter { it != "java/lang/Object" && it !in classes }
            val isFrameworkType = externalSupers.any { s ->
                s.endsWith("ImplBase") || s == "java/lang/AutoCloseable" || s == "java/io/Closeable" ||
                    FRAMEWORK_PREFIXES.any { s.startsWith(it) }
            }
            val isEntryClass = c.annotations.any { it in ENTRY_CLASS_ANNOS } || isFrameworkType
            if (isEntryClass) {
                markClass(c.name)
                c.methods.values.filter { it.key.name == "<init>" }.forEach { markMethod(it.key) }
            }
            for (m in c.methods.values) {
                val isMain = m.key.name == "main" && m.isStatic && m.key.desc in setOf("([Ljava/lang/String;)V", "()V")
                if (isMain || m.annotations.any { it in ENTRY_METHOD_ANNOS } || isFrameworkType) {
                    markMethod(m.key)
                }
            }
        }

        while (classQ.isNotEmpty() || methodQ.isNotEmpty()) {
            while (classQ.isNotEmpty()) {
                val c = classes[classQ.removeFirst()] ?: continue
                c.types.forEach(::markClass)
                classes["${c.name}\$Companion"]?.let { markClass(it.name) }
            }
            while (methodQ.isNotEmpty()) {
                val mKey = methodQ.removeFirst()
                val m = classes[mKey.owner]?.methods?.get(mKey.sig) ?: continue
                m.types.forEach(::markClass)
                m.calls.forEach(::markMethod)
            }
        }
    }

    val deadClasses = mutableListOf<ClassNode>()
    val deadMethods = mutableListOf<Pair<ClassNode, MethodNode>>()

    for (c in classes.values) {
        if (c.module.endsWith("-proto") || GENERATED_PREFIXES.any { c.name.startsWith(it) }) continue
        if (c.name.contains("\$inlined\$") || c.name.contains("\$WhenMappings") || c.name.endsWith("\$DefaultImpls")) continue
        if (c.name.contains('$') && c.name.substringAfterLast('$').all(Char::isDigit)) continue
        if (c.name.endsWith("Kt") && c.methods.values.none { !it.isSynthetic && it.key.name != "<clinit>" }) continue

        if (c.name !in reachableClasses) {
            deadClasses.add(c); continue
        }

        val isSpannerTable = "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;" in c.annotations
        val isDataClass = c.methods.keys.any { it.startsWith("copy(") }

        for (m in c.methods.values) {
            if (m.key in reachableMethods || m.isSynthetic || m.isAbstract) continue
            if (m.key.name in setOf("<init>", "<clinit>", "copy") || m.key.sig in OBJECT_METHODS) continue
            if (m.key.name.contains("\$lambda\$") || m.key.name.endsWith("\$default")) continue
            if (m.key.name.startsWith("component") && m.key.name.removePrefix("component").all(Char::isDigit)) continue
            if (c.isEnum && m.key.name in setOf("values", "valueOf", "getEntries")) continue
            if (isDataClass && (m.key.name.startsWith("get") || m.key.name.startsWith("is"))) continue
            if (isSpannerTable && (m.key.name.startsWith("set") || m.key.name.startsWith("get"))) continue
            deadMethods.add(c to m)
        }
    }

    println("ASM Dead Code Report (src/main only): $classFileCount classes | ${parseMs + analysisMs} ms (parse=${parseMs}ms, bfs=${analysisMs}ms)")
    println("Unreachable classes (${deadClasses.size}):")
    deadClasses.sortedWith(compareBy({ it.module }, { it.name })).forEach { println("  [${it.module}] ${it.name.replace('/', '.')}") }
    println("Unreachable methods (${deadMethods.size}):")
    deadMethods.sortedWith(compareBy({ it.first.module }, { it.second.key.toString() })).forEach { (c, m) -> println("  [${c.module}] ${m.key}") }
}
