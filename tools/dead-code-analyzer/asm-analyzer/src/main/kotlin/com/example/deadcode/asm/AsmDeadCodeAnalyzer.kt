package com.example.deadcode.asm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
data class SandboxMetadata(
    val modules: List<ModuleMetadata>,
)

@Serializable
data class ModuleMetadata(
    val path: String,
    val projectDir: String,
    val sourceRoots: List<String>,
    val generatedSourceRoots: List<String>,
    val classesDirs: List<String>,
    val compileClasspath: List<String>,
    val projectDependencies: List<String>,
)

data class MethodKey(
    val owner: String,
    val name: String,
    val descriptor: String,
) {
    override fun toString(): String = "${owner.replace('/', '.')}.$name$descriptor"
}

data class ClassInfo(
    val modulePath: String,
    val internalName: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val sourceFile: String?,
    val annotations: MutableSet<String> = mutableSetOf(),
    val methods: MutableMap<String, MethodInfo> = mutableMapOf(), // key = name + desc
    val fields: MutableSet<String> = mutableSetOf(),
    val typeRefs: MutableSet<String> = mutableSetOf(),
) {
    val isInterface: Boolean get() = (access and Opcodes.ACC_INTERFACE) != 0
    val isEnum: Boolean get() = (access and Opcodes.ACC_ENUM) != 0
    val isAnnotation: Boolean get() = (access and Opcodes.ACC_ANNOTATION) != 0
    val isAbstract: Boolean get() = (access and Opcodes.ACC_ABSTRACT) != 0
}

data class MethodInfo(
    val key: MethodKey,
    val access: Int,
    val annotations: MutableSet<String> = mutableSetOf(),
    val calledMethods: MutableSet<MethodKey> = mutableSetOf(),
    val referencedTypes: MutableSet<String> = mutableSetOf(),
    val referencedFields: MutableSet<Pair<String, String>> = mutableSetOf(),
) {
    val isSynthetic: Boolean get() = (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0
    val isAbstract: Boolean get() = (access and Opcodes.ACC_ABSTRACT) != 0
    val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
}

private val ENTRY_POINT_CLASS_ANNOTATIONS = setOf(
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

private val ENTRY_POINT_METHOD_ANNOTATIONS = setOf(
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

private val OBJECT_METHODS = setOf(
    "toString()Ljava/lang/String;",
    "hashCode()I",
    "equals(Ljava/lang/Object;)Z",
)

private val GENERATED_PACKAGE_PREFIXES = listOf(
    "com/example/identity/proto/",
    "com/example/todo/proto/",
    "com/example/gateway/api/", // OpenAPI generated
    "com/example/todo/domain/decision/generated/",
)

fun isGeneratedClass(internalName: String, modulePath: String): Boolean {
    if (modulePath.endsWith("-proto")) return true
    if (GENERATED_PACKAGE_PREFIXES.any { internalName.startsWith(it) }) return true
    if (internalName.contains("Grpc\$") || internalName.endsWith("OuterClass")) return true
    return false
}

fun extractTypesFromDescriptor(descriptor: String, out: MutableSet<String>) {
    fun addType(t: Type) {
        when (t.sort) {
            Type.OBJECT -> out.add(t.internalName)
            Type.ARRAY -> addType(t.elementType)
            Type.METHOD -> {
                t.argumentTypes.forEach { addType(it) }
                addType(t.returnType)
            }
        }
    }
    try {
        addType(Type.getType(descriptor))
    } catch (_: Exception) {
        // ignore malformed descriptors
    }
}

fun main(args: Array<String>) {
    val metadataPath = args.firstOrNull()
        ?: error("Usage: AsmDeadCodeAnalyzer <path-to-sandbox-metadata.json>")
    val metadataFile = File(metadataPath)
    val metadata = Json.decodeFromString<SandboxMetadata>(metadataFile.readText())

    val classes = linkedMapOf<String, ClassInfo>()
    var classFileCount = 0

    val parseMs = measureTimeMillis {
        for (module in metadata.modules) {
            for (dirPath in module.classesDirs) {
                val dir = File(dirPath)
                if (!dir.exists()) continue
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { classFile ->
                        classFileCount++
                        val reader = ClassReader(classFile.readBytes())
                        var currentClass: ClassInfo? = null

                        reader.accept(
                            object : ClassVisitor(Opcodes.ASM9) {
                                override fun visit(
                                    version: Int,
                                    access: Int,
                                    name: String,
                                    signature: String?,
                                    superName: String?,
                                    interfaces: Array<out String>?,
                                ) {
                                    val info = ClassInfo(
                                        modulePath = module.path,
                                        internalName = name,
                                        superName = superName,
                                        interfaces = interfaces?.toList().orEmpty(),
                                        access = access,
                                        sourceFile = null,
                                    )
                                    superName?.let { info.typeRefs.add(it) }
                                    interfaces?.forEach { info.typeRefs.add(it) }
                                    currentClass = info
                                    classes[name] = info
                                }

                                override fun visitSource(source: String?, debug: String?) {
                                    val c = currentClass ?: return
                                    classes[c.internalName] = c.copy(sourceFile = source)
                                    currentClass = classes[c.internalName]
                                }

                                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                                    currentClass?.annotations?.add(descriptor)
                                    extractTypesFromDescriptor(descriptor, currentClass!!.typeRefs)
                                    return null
                                }

                                override fun visitField(
                                    access: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    value: Any?,
                                ): FieldVisitor? {
                                    val c = currentClass ?: return null
                                    c.fields.add(name)
                                    extractTypesFromDescriptor(descriptor, c.typeRefs)
                                    return null
                                }

                                override fun visitMethod(
                                    access: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    exceptions: Array<out String>?,
                                ): MethodVisitor {
                                    val c = currentClass!!
                                    val key = MethodKey(c.internalName, name, descriptor)
                                    val mInfo = MethodInfo(key = key, access = access)
                                    extractTypesFromDescriptor(descriptor, mInfo.referencedTypes)
                                    exceptions?.forEach { mInfo.referencedTypes.add(it) }
                                    c.methods[name + descriptor] = mInfo

                                    return object : MethodVisitor(Opcodes.ASM9) {
                                        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                                            mInfo.annotations.add(desc)
                                            extractTypesFromDescriptor(desc, mInfo.referencedTypes)
                                            return null
                                        }

                                        override fun visitMethodInsn(
                                            opcode: Int,
                                            owner: String,
                                            mName: String,
                                            mDesc: String,
                                            isInterface: Boolean,
                                        ) {
                                            mInfo.calledMethods.add(MethodKey(owner, mName, mDesc))
                                            mInfo.referencedTypes.add(owner)
                                            extractTypesFromDescriptor(mDesc, mInfo.referencedTypes)
                                        }

                                        override fun visitFieldInsn(
                                            opcode: Int,
                                            owner: String,
                                            fName: String,
                                            fDesc: String,
                                        ) {
                                            mInfo.referencedFields.add(owner to fName)
                                            mInfo.referencedTypes.add(owner)
                                            extractTypesFromDescriptor(fDesc, mInfo.referencedTypes)
                                        }

                                        override fun visitTypeInsn(opcode: Int, type: String) {
                                            if (type.startsWith("[")) {
                                                extractTypesFromDescriptor(type, mInfo.referencedTypes)
                                            } else {
                                                mInfo.referencedTypes.add(type)
                                            }
                                        }

                                        override fun visitLdcInsn(value: Any?) {
                                            if (value is Type) {
                                                extractTypesFromDescriptor(value.descriptor, mInfo.referencedTypes)
                                            }
                                        }

                                        override fun visitInvokeDynamicInsn(
                                            indName: String,
                                            indDesc: String,
                                            bsm: Handle,
                                            vararg bsmArgs: Any?,
                                        ) {
                                            extractTypesFromDescriptor(indDesc, mInfo.referencedTypes)
                                            for (arg in bsmArgs) {
                                                when (arg) {
                                                    is Handle -> {
                                                        mInfo.calledMethods.add(
                                                            MethodKey(arg.owner, arg.name, arg.desc),
                                                        )
                                                        mInfo.referencedTypes.add(arg.owner)
                                                    }
                                                    is Type -> extractTypesFromDescriptor(
                                                        arg.descriptor,
                                                        mInfo.referencedTypes,
                                                    )
                                                }
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
    }

    // Build subtype map so virtual/interface calls propagate to overriding implementations
    val subtypes = mutableMapOf<String, MutableSet<String>>()
    val supertypes = mutableMapOf<String, MutableSet<String>>()
    for (c in classes.values) {
        c.superName?.let { sup ->
            subtypes.computeIfAbsent(sup) { mutableSetOf() }.add(c.internalName)
            supertypes.computeIfAbsent(c.internalName) { mutableSetOf() }.add(sup)
        }
        for (iface in c.interfaces) {
            subtypes.computeIfAbsent(iface) { mutableSetOf() }.add(c.internalName)
            supertypes.computeIfAbsent(c.internalName) { mutableSetOf() }.add(iface)
        }
    }

    fun allSubtypesOf(internalName: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(internalName)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (sub in subtypes[cur].orEmpty()) {
                if (result.add(sub)) queue.add(sub)
            }
        }
        return result
    }

    fun allSupertypesOf(internalName: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(internalName)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (sup in supertypes[cur].orEmpty()) {
                if (result.add(sup)) queue.add(sup)
            }
        }
        return result
    }

    fun isExternalFrameworkOverride(c: ClassInfo, methodSig: String): Boolean {
        // If class extends/implements an external class (e.g. gRPC ImplBase, Spring Filter, OpenAPI interface, SpannerRepository)
        // and the method isn't declared in any internal supertype, treat it as a framework override candidate.
        val externalSupers = buildList {
            c.superName?.let { if (it != "java/lang/Object" && it !in classes) add(it) }
            c.interfaces.filter { it !in classes }.forEach { add(it) }
        }
        if (externalSupers.isEmpty()) return false
        // Spring / gRPC / Servlet / OpenAPI / Spanner / OpenTelemetry / AutoCloseable interfaces
        return externalSupers.any { sup ->
            sup.endsWith("ImplBase") ||
                sup == "java/lang/AutoCloseable" ||
                sup == "java/io/Closeable" ||
                sup.startsWith("org/springframework/") ||
                sup.startsWith("jakarta/servlet/") ||
                sup.startsWith("io/grpc/") ||
                sup.startsWith("io/opentelemetry/") ||
                sup.startsWith("com/google/cloud/spring/") ||
                sup.startsWith("com/example/gateway/api/")
        }
    }

    val reachableClasses = mutableSetOf<String>()
    val reachableMethods = mutableSetOf<MethodKey>()
    val methodQueue = ArrayDeque<MethodKey>()
    val classQueue = ArrayDeque<String>()

    fun markClassReachable(internalName: String) {
        if (internalName !in classes) return
        if (!reachableClasses.add(internalName)) return
        classQueue.add(internalName)
        val c = classes[internalName]!!
        // <clinit> runs automatically when class is loaded
        c.methods["<clinit>()V"]?.let {
            if (reachableMethods.add(it.key)) methodQueue.add(it.key)
        }
        // Enclosing class if inner/companion/lambda class
        val dollarIdx = internalName.lastIndexOf('$')
        if (dollarIdx > 0) {
            markClassReachable(internalName.substring(0, dollarIdx))
        }
        // Supertype / interfaces are loaded with the class
        c.superName?.let { markClassReachable(it) }
        c.interfaces.forEach { markClassReachable(it) }
        // If it's a Spring component / DTO / Enum / Exception, constructors & getters/setters might be invoked reflectively
        if (c.isEnum) {
            c.methods.values.filter { it.key.name in setOf("values", "valueOf", "getEntries") }.forEach {
                if (reachableMethods.add(it.key)) methodQueue.add(it.key)
            }
        }
    }

    fun markMethodReachable(key: MethodKey) {
        val c = classes[key.owner] ?: return
        markClassReachable(key.owner)
        val sig = key.name + key.descriptor
        val direct = c.methods[sig]
        if (direct != null && reachableMethods.add(direct.key)) {
            methodQueue.add(direct.key)
        }
        // Propagate up to supertypes (inherited method call on subtype)
        if (direct == null) {
            for (sup in allSupertypesOf(key.owner)) {
                val supMethod = classes[sup]?.methods?.get(sig)
                if (supMethod != null && reachableMethods.add(supMethod.key)) {
                    markClassReachable(sup)
                    methodQueue.add(supMethod.key)
                }
            }
        }
        // Propagate down to subtypes (polymorphic dispatch)
        if (key.name != "<init>" && key.name != "<clinit>") {
            for (sub in allSubtypesOf(key.owner)) {
                val subMethod = classes[sub]?.methods?.get(sig)
                if (subMethod != null && reachableMethods.add(subMethod.key)) {
                    markClassReachable(sub)
                    methodQueue.add(subMethod.key)
                }
            }
        }
    }

    val analysisMs = measureTimeMillis {
        // Seed entry points
        for (c in classes.values) {
            val isSpringBean = c.annotations.any { it in ENTRY_POINT_CLASS_ANNOTATIONS }
            val isGrpcImpl = c.superName?.endsWith("ImplBase") == true
            val isSpringInterface = c.interfaces.any {
                it.startsWith("org/springframework/") ||
                    it.startsWith("com/google/cloud/spring/") ||
                    it.startsWith("io/grpc/") ||
                    it.startsWith("com/example/gateway/api/")
            } || c.superName?.startsWith("org/springframework/") == true

            if (isSpringBean || isGrpcImpl || isSpringInterface) {
                markClassReachable(c.internalName)
                // Constructors of beans/components are invoked by Spring DI
                c.methods.values.filter { it.key.name == "<init>" }.forEach {
                    markMethodReachable(it.key)
                }
            }

            for ((sig, m) in c.methods) {
                val isMain = m.key.name == "main" && m.isStatic &&
                    (m.key.descriptor == "([Ljava/lang/String;)V" || m.key.descriptor == "()V")
                val hasEntryAnno = m.annotations.any { it in ENTRY_POINT_METHOD_ANNOTATIONS }
                val isFrameworkOverride = (isSpringBean || isGrpcImpl || isSpringInterface) &&
                    isExternalFrameworkOverride(c, sig)
                if (isMain || hasEntryAnno || isFrameworkOverride) {
                    markMethodReachable(m.key)
                }
            }
        }

        // BFS propagation
        while (methodQueue.isNotEmpty() || classQueue.isNotEmpty()) {
            while (classQueue.isNotEmpty()) {
                val cName = classQueue.removeFirst()
                val c = classes[cName] ?: continue
                c.typeRefs.forEach { markClassReachable(it) }
                // For reachable classes, if a class implements an internal interface / Spring / gRPC / Jackson DTO,
                // mark companion & outer linkages as reachable
                classes["$cName\$Companion"]?.let { markClassReachable(it.internalName) }
            }
            while (methodQueue.isNotEmpty()) {
                val mKey = methodQueue.removeFirst()
                val mInfo = classes[mKey.owner]?.methods?.get(mKey.name + mKey.descriptor) ?: continue
                mInfo.referencedTypes.forEach { markClassReachable(it) }
                mInfo.calledMethods.forEach { markMethodReachable(it) }
            }
        }
    }

    // Collect dead declarations (excluding generated code, synthetic/compiler-generated methods, Object methods, data class boilerplate)
    val deadClasses = mutableListOf<ClassInfo>()
    val deadMethods = mutableListOf<Pair<ClassInfo, MethodInfo>>()

    for (c in classes.values) {
        if (isGeneratedClass(c.internalName, c.modulePath)) continue
        if (c.internalName.contains("\$inlined\$") || c.internalName.contains("\$WhenMappings")) continue
        if (c.internalName.endsWith("\$DefaultImpls")) continue
        // Skip anonymous/lambda classes (handled via their enclosing method)
        if (c.internalName.substringAfterLast('$').all { it.isDigit() } && c.internalName.contains('$')) continue
        // Skip Kotlin file facade classes that only held compile-time `const val`s (0 non-synthetic methods)
        if (c.internalName.endsWith("Kt") && c.methods.values.none { !it.isSynthetic && it.key.name != "<clinit>" }) {
            continue
        }

        if (c.internalName !in reachableClasses) {
            deadClasses.add(c)
            continue
        }

        val isSpannerTable = "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;" in c.annotations
        val isDataClass = c.methods.keys.any { it.startsWith("copy(") }

        // Class is reachable -> inspect individual methods
        for ((sig, m) in c.methods) {
            if (m.key in reachableMethods) continue
            if (m.isSynthetic || m.isAbstract) continue
            if (m.key.name in setOf("<init>", "<clinit>")) continue
            if (sig in OBJECT_METHODS) continue
            // Kotlin compiler-generated lambda / default / data class methods
            if (m.key.name.contains("\$lambda\$")) continue
            if (m.key.name.startsWith("component") && m.key.name.removePrefix("component").all { it.isDigit() }) continue
            if (m.key.name == "copy" || m.key.name.endsWith("\$default")) continue
            // Enum boilerplate
            if (c.isEnum && m.key.name in setOf("values", "valueOf", "getEntries")) continue
            // Data class getters (Jackson/Spanner) & @Table entity setters (Spanner reflection)
            if (isDataClass && (m.key.name.startsWith("get") || m.key.name.startsWith("is"))) continue
            if (isSpannerTable && (m.key.name.startsWith("set") || m.key.name.startsWith("get"))) continue

            deadMethods.add(c to m)
        }
    }

    println("============================================================")
    println("ASM Dead Code Analyzer Report (Production / src/main only)")
    println("============================================================")
    println("Scanned class files : $classFileCount")
    println("Parse time          : ${parseMs} ms")
    println("Graph analysis time : ${analysisMs} ms")
    println("Total time          : ${parseMs + analysisMs} ms")
    println("Unreachable classes : ${deadClasses.size}")
    println("Unreachable methods : ${deadMethods.size}")
    println()

    println("--- Unreachable Classes (${deadClasses.size}) ---")
    deadClasses.sortedWith(compareBy({ it.modulePath }, { it.internalName })).forEach { c ->
        println("  [${c.modulePath}] ${c.internalName.replace('/', '.')} (${c.sourceFile ?: "?"})")
    }
    println()

    println("--- Unreachable Methods in Reachable Classes (${deadMethods.size}) ---")
    deadMethods.sortedWith(compareBy({ it.first.modulePath }, { it.second.key.toString() })).forEach { (c, m) ->
        println("  [${c.modulePath}] ${m.key} (${c.sourceFile ?: "?"})")
    }
}
