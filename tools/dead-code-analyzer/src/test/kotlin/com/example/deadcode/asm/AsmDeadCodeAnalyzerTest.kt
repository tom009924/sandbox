package com.example.deadcode.asm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class AsmDeadCodeAnalyzerTest {
    private val analyzer = AsmDeadCodeAnalyzer()

    private fun buildClass(
        name: String,
        access: Int = Opcodes.ACC_PUBLIC,
        superName: String = "java/lang/Object",
        interfaces: Array<String>? = null,
        annotations: List<String> = emptyList(),
        block: ClassWriter.() -> Unit = {},
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, access, name, null, superName, interfaces)
        annotations.forEach { cw.visitAnnotation(it, true).visitEnd() }
        cw.block()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun ClassWriter.addMethod(
        name: String,
        desc: String = "()V",
        access: Int = Opcodes.ACC_PUBLIC,
        signature: String? = null,
        annotations: List<String> = emptyList(),
        body: MethodVisitor.() -> Unit = {},
    ) {
        val mv = visitMethod(access, name, desc, signature, null)
        annotations.forEach { mv.visitAnnotation(it, true).visitEnd() }
        if ((access and Opcodes.ACC_ABSTRACT) == 0) {
            mv.visitCode()
            mv.body()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(4, 4)
        }
        mv.visitEnd()
    }

    @Test
    fun `detects unreferenced method and keeps called method`() {
        val app = buildClass("com/example/App") {
            addMethod("main", "([Ljava/lang/String;)V", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC) {
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Helper", "used", "()V", false)
            }
        }
        val helper = buildClass("com/example/Helper") {
            addMethod("used")
            addMethod("unused")
        }

        val report = analyzer.analyze(listOf(ClassInput(":app", app), ClassInput(":app", helper)))

        assertEquals(emptyList<String>(), report.deadClasses)
        assertEquals(listOf("method:[:app] com.example.Helper.unused()V"), report.deadMethods)
    }

    @Test
    fun `treats overriding method as referenced when called via interface`() {
        val caller = buildClass("com/example/Caller", annotations = listOf("Lorg/springframework/web/bind/annotation/RestController;")) {
            addMethod("handle", annotations = listOf("Lorg/springframework/web/bind/annotation/GetMapping;")) {
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/example/Repo", "save", "()V", true)
            }
        }
        val repo = buildClass("com/example/Repo", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) {
            addMethod("save", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT)
        }
        val impl = buildClass(
            "com/example/RepoImpl",
            interfaces = arrayOf("com/example/Repo"),
            annotations = listOf("Lorg/springframework/stereotype/Repository;"),
        ) {
            addMethod("save")
            addMethod("unusedExtra")
        }

        val report = analyzer.analyze(listOf(ClassInput(":svc", caller), ClassInput(":svc", repo), ClassInput(":svc", impl)))

        assertEquals(emptyList<String>(), report.deadClasses)
        assertEquals(listOf("method:[:svc] com.example.RepoImpl.unusedExtra()V"), report.deadMethods)
    }

    @Test
    fun `detects uninjected Spring Service bean`() {
        val deadBean = buildClass("com/example/DeadService", annotations = listOf("Lorg/springframework/stereotype/Service;")) {
            addMethod("work")
        }

        val report = analyzer.analyze(listOf(ClassInput(":svc", deadBean)))

        assertEquals(listOf("class:[:svc] com.example.DeadService [unused bean]"), report.deadClasses)
        assertEquals(emptyList<String>(), report.deadMethods)
    }

    @Test
    fun `keeps Component bean alive when injected via generic List parameter`() {
        val controller = buildClass("com/example/MyController", annotations = listOf("Lorg/springframework/web/bind/annotation/RestController;")) {
            addMethod(
                name = "<init>",
                desc = "(Ljava/util/List;)V",
                signature = "(Ljava/util/List<Lcom/example/Handler;>;)V",
            )
        }
        val handlerIface = buildClass("com/example/Handler", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)
        val handlerImpl = buildClass(
            "com/example/MyHandler",
            interfaces = arrayOf("com/example/Handler"),
            annotations = listOf("Lorg/springframework/stereotype/Component;"),
        )

        val report = analyzer.analyze(
            listOf(ClassInput(":svc", controller), ClassInput(":svc", handlerIface), ClassInput(":svc", handlerImpl)),
        )

        assertEquals(emptyList<String>(), report.deadClasses)
        assertEquals(emptyList<String>(), report.deadMethods)
    }

    @Test
    fun `detects unused Bean returning local type and keeps Bean returning external type`() {
        val unusedType = buildClass("com/example/UnusedLocal")
        val config = buildClass("com/example/AppConfig", annotations = listOf("Lorg/springframework/context/annotation/Configuration;")) {
            addMethod("unusedLocal", "()Lcom/example/UnusedLocal;", annotations = listOf("Lorg/springframework/context/annotation/Bean;"))
            addMethod("externalBean", "()Ljava/lang/String;", annotations = listOf("Lorg/springframework/context/annotation/Bean;"))
        }

        val report = analyzer.analyze(listOf(ClassInput(":svc", unusedType), ClassInput(":svc", config)))

        assertEquals(listOf("class:[:svc] com.example.UnusedLocal"), report.deadClasses)
        assertEquals(
            listOf("method:[:svc] com.example.AppConfig.unusedLocal()Lcom/example/UnusedLocal; [unused @Bean]"),
            report.deadMethods,
        )
    }

    @Test
    fun `ignores Kotlin data class boilerplate, Table setters, and generated packages`() {
        val controller = buildClass("com/example/C", annotations = listOf("Lorg/springframework/web/bind/annotation/RestController;")) {
            addMethod("get", "()Lcom/example/Dto;", annotations = listOf("Lorg/springframework/web/bind/annotation/GetMapping;"))
        }
        val dto = buildClass("com/example/Dto") {
            addMethod("copy", "()Lcom/example/Dto;")
            addMethod("component1", "()Ljava/lang/String;")
            addMethod("getName", "()Ljava/lang/String;")
            addMethod("foo\$default", "()V")
        }
        val entity = buildClass("com/example/Entity", annotations = listOf("Lcom/google/cloud/spring/data/spanner/core/mapping/Table;")) {
            addMethod("setId", "(Ljava/lang/String;)V")
            addMethod("getId", "()Ljava/lang/String;")
        }
        val generated = buildClass("com/example/gateway/presentation/model/GeneratedModel") {
            addMethod("forValue", "(Ljava/lang/String;)V")
        }

        val report = analyzer.analyze(
            listOf(
                ClassInput(":svc", controller),
                ClassInput(":svc", dto),
                ClassInput(":svc", entity),
                ClassInput(":svc", generated),
            ),
        )

        assertTrue(report.findings.isEmpty(), "Expected no findings but got: ${report.findings}")
    }
}
