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
import kotlin.system.measureTimeMillis

data class ClassInput(
    val module: String,
    val bytes: ByteArray,
)

data class MethodId(
    val owner: String,
    val name: String,
    val desc: String,
) {
    override fun toString() = "${owner.replace('/', '.')}.$name$desc"
}

data class AnalysisReport(
    val scannedClasses: Int,
    val deadClasses: List<String>,
    val deadMethods: List<String>,
    val elapsedMs: Long,
) {
    val findings: List<String> get() = deadClasses + deadMethods
}

class AsmDeadCodeAnalyzer {
    fun discoverProductionClasses(rootDir: File): List<ClassInput> =
        listOf("libs", "services")
            .map { File(rootDir, it) }
            .filter { it.isDirectory }
            .flatMap { group ->
                group.listFiles()?.filter { it.isDirectory }.orEmpty().map { ":${group.name}:${it.name}" to it }
            }
            .flatMap { (mod, dir) ->
                listOf("build/classes/kotlin/main", "build/classes/java/main")
                    .map { File(dir, it) }
                    .filter { it.isDirectory }
                    .flatMap { classDir ->
                        classDir.walkTopDown()
                            .filter { it.isFile && it.extension == "class" }
                            .map { ClassInput(mod, it.readBytes()) }
                            .toList()
                    }
            }

    fun analyze(inputs: List<ClassInput>): AnalysisReport {
        val state = ScanState()
        val elapsedMs = measureTimeMillis {
            inputs.forEach { scanClass(it, state) }
            propagateDiBeans(state)
            resolveBeanFactories(state)
            propagateSupertypeCalls(state)
        }

        val deadClassMap = state.classModules.filterKeys { cls ->
            cls !in state.skippedClasses &&
                cls !in state.referencedClasses &&
                !cls.endsWith("\$Companion")
        }
        val deadMethodMap = state.declaredMethods.filterKeys { m ->
            m !in state.calledMethods && m.owner !in deadClassMap
        }

        val deadClasses = deadClassMap.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .map { (cls, mod) ->
                val tag = if (cls in state.diBeanClasses) " [unused bean]" else ""
                "class:[$mod] ${cls.replace('/', '.')}$tag"
            }

        val deadMethods = deadMethodMap.entries
            .sortedWith(compareBy({ it.value }, { it.key.toString() }))
            .map { (method, mod) ->
                val tag = if (method in state.beanFactoryMethods) " [unused @Bean]" else ""
                "method:[$mod] $method$tag"
            }

        return AnalysisReport(
            scannedClasses = inputs.size,
            deadClasses = deadClasses,
            deadMethods = deadMethods,
            elapsedMs = elapsedMs,
        )
    }

    private fun scanClass(input: ClassInput, state: ScanState) {
        ClassReader(input.bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                lateinit var cls: String
                var isEnum = false
                var isDataClass = false
                var isTable = false
                var isFrameworkSub = false
                val localMethods = mutableListOf<Pair<MethodId, Int>>()
                val entryMethods = mutableSetOf<MethodId>()

                override fun visit(
                    v: Int,
                    access: Int,
                    name: String,
                    sig: String?,
                    sup: String?,
                    ifaces: Array<out String>?,
                ) {
                    cls = name
                    isEnum = (access and Opcodes.ACC_ENUM) != 0
                    state.classModules[name] = input.module

                    val supers = listOfNotNull(sup).filter { it != "java/lang/Object" } + ifaces.orEmpty()
                    supers.forEach { s ->
                        state.subtypes.computeIfAbsent(s) { mutableSetOf() }.add(name)
                        state.supertypes.computeIfAbsent(name) { mutableSetOf() }.add(s)
                    }

                    isFrameworkSub = supers.any(::isExternalFrameworkType)
                    if (isFrameworkSub) state.referencedClasses.add(name)
                    if (isSkippedClass(name, input.module)) state.skippedClasses.add(name)
                }

                override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
                    addTypes(desc, state.referencedClasses)
                    if (desc in ENTRY_CLASS_ANNOS) state.referencedClasses.add(cls)
                    if (desc in DI_BEAN_ANNOS) state.diBeanClasses.add(cls)
                    if (desc == TABLE_ANNO) isTable = true
                    return annoValueVisitor(state.referencedClasses)
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    desc: String,
                    sig: String?,
                    value: Any?,
                ): FieldVisitor? {
                    addTypes(desc, state.referencedClasses)
                    addSignatureTypes(sig, state.referencedClasses)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    sig: String?,
                    ex: Array<out String>?,
                ): MethodVisitor {
                    val id = MethodId(cls, name, desc)
                    if (name == "main") state.referencedClasses.add(cls)
                    if (name == "copy") isDataClass = true
                    localMethods.add(id to access)

                    val argTypes = mutableSetOf<String>()
                    val retType = runCatching { Type.getReturnType(desc) }.getOrNull()
                    runCatching { Type.getArgumentTypes(desc).forEach { addTypes(it.descriptor, argTypes) } }
                    ex?.forEach(argTypes::add)
                    var isBeanMethod = false

                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(d: String, v: Boolean): AnnotationVisitor {
                            addTypes(d, state.referencedClasses)
                            if (d in ENTRY_METHOD_ANNOS) {
                                entryMethods.add(id)
                                state.referencedClasses.add(cls)
                            }
                            if (d == BEAN_METHOD_ANNO) isBeanMethod = true
                            return annoValueVisitor(state.referencedClasses)
                        }

                        override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                            state.recordCall(cls, owner, n, d)
                            addTypes(d, state.referencedClasses)
                        }

                        override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                            if (owner != cls) state.referencedClasses.add(owner)
                            addTypes(d, state.referencedClasses)
                        }

                        override fun visitTypeInsn(op: Int, type: String) {
                            if (type.startsWith("[")) {
                                addTypes(type, state.referencedClasses)
                            } else if (type != cls) {
                                state.referencedClasses.add(type)
                            }
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type) addTypes(value.descriptor, state.referencedClasses)
                        }

                        override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg bsmArgs: Any?) {
                            addTypes(d, state.referencedClasses)
                            for (arg in bsmArgs) {
                                when (arg) {
                                    is Handle -> state.recordCall(cls, arg.owner, arg.name, arg.desc)
                                    is Type -> addTypes(arg.descriptor, state.referencedClasses)
                                }
                            }
                        }

                        override fun visitEnd() {
                            state.referencedClasses.addAll(argTypes)
                            if (isBeanMethod) {
                                val retInternal = retType?.takeIf { it.sort == Type.OBJECT }?.internalName
                                state.beanFactoryMethods[id] = retInternal
                            } else {
                                retType?.let { addTypes(it.descriptor, state.referencedClasses) }
                                addSignatureTypes(sig, state.referencedClasses)
                            }
                        }
                    }
                }

                override fun visitEnd() {
                    if (cls.endsWith("Kt") && localMethods.none { (m, a) ->
                            (a and Opcodes.ACC_SYNTHETIC) == 0 && m.name != "<clinit>"
                        }
                    ) {
                        state.skippedClasses.add(cls)
                    }
                    if (cls in state.skippedClasses || isFrameworkSub) return
                    for ((m, access) in localMethods) {
                        if (!isIgnoredMethod(m, access, m in entryMethods, isEnum, isDataClass, isTable)) {
                            state.declaredMethods[m] = input.module
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }

    private fun propagateDiBeans(state: ScanState) {
        val queue = ArrayDeque(state.referencedClasses)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (sub in state.subtypes[cur].orEmpty()) {
                if (sub in state.diBeanClasses && seen.add(sub)) {
                    state.referencedClasses.add(sub)
                    queue.add(sub)
                }
            }
        }
    }

    private fun resolveBeanFactories(state: ScanState) {
        for ((beanMethod, retInternal) in state.beanFactoryMethods) {
            val isExternal = retInternal == null || retInternal !in state.classModules
            val isUsed = isExternal ||
                retInternal in state.referencedClasses ||
                state.supertypes[retInternal].orEmpty().any { it !in state.classModules || it in state.referencedClasses }
            if (isUsed) state.calledMethods.add(beanMethod)
        }
    }

    private fun propagateSupertypeCalls(state: ScanState) {
        for ((owner, sigs) in state.calledSigsByOwner) {
            val queue = ArrayDeque(state.subtypes[owner].orEmpty())
            val seen = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val sub = queue.removeFirst()
                if (!seen.add(sub)) continue
                sigs.forEach { sig ->
                    state.calledMethods.add(MethodId(sub, sig.substringBefore('('), "(" + sig.substringAfter('(')))
                }
                queue.addAll(state.subtypes[sub].orEmpty())
            }
        }
    }

    private class ScanState {
        val classModules = linkedMapOf<String, String>()
        val declaredMethods = linkedMapOf<MethodId, String>()
        val referencedClasses = mutableSetOf<String>()
        val calledMethods = mutableSetOf<MethodId>()
        val calledSigsByOwner = mutableMapOf<String, MutableSet<String>>()
        val subtypes = mutableMapOf<String, MutableSet<String>>()
        val supertypes = mutableMapOf<String, MutableSet<String>>()
        val diBeanClasses = mutableSetOf<String>()
        val beanFactoryMethods = mutableMapOf<MethodId, String?>()
        val skippedClasses = mutableSetOf<String>()

        fun recordCall(callerClass: String, owner: String, name: String, desc: String) {
            calledMethods.add(MethodId(owner, name, desc))
            calledSigsByOwner.computeIfAbsent(owner) { mutableSetOf() }.add(name + desc)
            if (owner != callerClass) referencedClasses.add(owner)
        }
    }

    companion object {
        private const val TABLE_ANNO = "Lcom/google/cloud/spring/data/spanner/core/mapping/Table;"
        private const val BEAN_METHOD_ANNO = "Lorg/springframework/context/annotation/Bean;"

        private val ENTRY_CLASS_ANNOS = setOf(
            "Lorg/springframework/boot/autoconfigure/SpringBootApplication;",
            "Lorg/springframework/stereotype/Controller;",
            "Lorg/springframework/web/bind/annotation/RestController;",
            "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
            "Lorg/springframework/web/bind/annotation/RestControllerAdvice;",
            "Lorg/springframework/context/annotation/Configuration;",
            TABLE_ANNO,
        )

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

        private val FRAMEWORK_PREFIXES = listOf(
            "org/springframework/",
            "io/grpc/",
            "io/opentelemetry/",
            "com/google/cloud/spring/",
            "com/example/gateway/presentation/api/",
        )

        private val GENERATED_PREFIXES = listOf(
            "com/example/identity/proto/",
            "com/example/todo/proto/",
            "com/example/gateway/api/",
            "com/example/gateway/presentation/api/",
            "com/example/gateway/presentation/model/",
            "com/example/todo/domain/decision/generated/",
        )

        private val STANDARD_IGNORED_METHODS = setOf("<init>", "<clinit>", "main", "copy", "toString", "hashCode", "equals")
        private val ENUM_METHODS = setOf("values", "valueOf", "getEntries")

        internal fun isExternalFrameworkType(internalName: String): Boolean =
            internalName.endsWith("ImplBase") ||
                internalName == "java/lang/AutoCloseable" ||
                internalName == "java/io/Closeable" ||
                FRAMEWORK_PREFIXES.any(internalName::startsWith)

        internal fun isSkippedClass(internalName: String, module: String): Boolean =
            module.endsWith("-proto") ||
                GENERATED_PREFIXES.any(internalName::startsWith) ||
                internalName.contains("\$inlined\$") ||
                internalName.contains("\$WhenMappings") ||
                internalName.endsWith("\$DefaultImpls") ||
                (internalName.contains('$') && internalName.substringAfterLast('$').all(Char::isDigit))

        internal fun isIgnoredMethod(
            m: MethodId,
            access: Int,
            isEntry: Boolean,
            isEnum: Boolean,
            isDataClass: Boolean,
            isTable: Boolean,
        ): Boolean {
            val isSyntheticOrAbstract = (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_ABSTRACT)) != 0
            if (isSyntheticOrAbstract || isEntry) return true
            if (m.name in STANDARD_IGNORED_METHODS) return true
            if (m.name.contains("\$lambda\$") || m.name.endsWith("\$default")) return true
            if (m.name.startsWith("component") && m.name.removePrefix("component").all(Char::isDigit)) return true
            if (isEnum && m.name in ENUM_METHODS) return true
            if (isDataClass && (m.name.startsWith("get") || m.name.startsWith("is"))) return true
            if (isTable && (m.name.startsWith("get") || m.name.startsWith("set"))) return true
            return false
        }

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
    }
}
