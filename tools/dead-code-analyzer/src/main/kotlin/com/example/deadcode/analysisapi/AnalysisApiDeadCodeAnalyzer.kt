package com.example.deadcode.analysisapi

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyAccessorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

@Serializable
private data class Metadata(val sourceRoots: List<String>, val classpath: List<String>)

private val ENTRY_CLASS_ANNOS = setOf(
    "SpringBootApplication", "Component", "Service", "Repository", "Controller",
    "RestController", "ControllerAdvice", "RestControllerAdvice",
    "Configuration", "ConfigurationProperties", "Table",
)

private val ENTRY_CALLABLE_ANNOS = setOf(
    "Bean", "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
    "PatchMapping", "DeleteMapping", "ExceptionHandler", "Scheduled",
    "EventListener", "PostConstruct", "PreDestroy", "JsonCreator", "JsonValue",
)

private val OBJECT_METHODS = setOf("toString", "hashCode", "equals")

private fun KtDeclaration.hasAnno(names: Set<String>) = annotationEntries.any { it.shortName?.asString() in names }
private fun KtDeclaration.isCompanionObj() = this is KtObjectDeclaration && isCompanion()
private fun PsiElement.lineNum() = containingFile?.viewProvider?.document?.let { it.getLineNumber(textOffset) + 1 } ?: 0

private fun KtDeclaration.fqLabel(): String {
    val pkg = containingKtFile.packageFqName.asString()
    val owners = parents.filterIsInstance<KtClassOrObject>()
        .mapNotNull { it.name ?: if (it.isCompanionObj()) "Companion" else null }.toList().reversed()
    val self = name ?: if (isCompanionObj()) "Companion" else "<anon>"
    return (listOfNotNull(pkg.takeIf { it.isNotEmpty() }) + owners + self).joinToString(".") +
        if (this is KtNamedFunction) "()" else ""
}

fun main(args: Array<String>) {
    val meta = Json.decodeFromString<Metadata>(File(args.firstOrNull() ?: error("Pass metadata JSON path")).readText())
    val disposable = Disposer.newDisposable("DeadCodeAnalysis")
    try {
        val platform = JvmPlatforms.defaultJvmPlatform
        lateinit var session: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
        val sessionMs = measureTimeMillis {
            session = buildStandaloneAnalysisAPISession(projectDisposable = disposable) {
                buildKtModuleProvider {
                    this.platform = platform
                    val jdk = addModule(
                        buildKtSdkModule {
                            this.platform = platform
                            libraryName = "JDK"
                            addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                        },
                    )
                    val libs = addModule(
                        buildKtLibraryModule {
                            this.platform = platform
                            libraryName = "classpath"
                            meta.classpath.map(Path::of).filter { it.exists() }.forEach(::addBinaryRoot)
                        },
                    )
                    addModule(
                        buildKtSourceModule {
                            this.platform = platform
                            moduleName = "sandbox-main"
                            meta.sourceRoots.map(Path::of).filter { it.exists() }.forEach(::addSourceRoot)
                            addRegularDependency(jdk)
                            addRegularDependency(libs)
                        },
                    )
                }
            }
        }

        val ktFiles = session.modulesWithFiles.entries
            .filter { it.key is KaSourceModule }
            .flatMap { it.value.filterIsInstance<KtFile>() }

        val decls = linkedSetOf<KtDeclaration>()
        val entryPoints = mutableSetOf<KtDeclaration>()
        val edges = mutableMapOf<KtDeclaration, MutableSet<KtDeclaration>>()
        fun addEdge(from: KtDeclaration, to: KtDeclaration) {
            if (from != to) edges.computeIfAbsent(from) { mutableSetOf() }.add(to)
        }

        // Pass 1: Collect production declarations and initial entry points
        for (file in ktFiles) {
            file.accept(
                object : KtTreeVisitorVoid() {
                    override fun visitClassOrObject(c: KtClassOrObject) {
                        if (c !is KtEnumEntry && !c.isLocal) {
                            decls.add(c)
                            if (c.hasAnno(ENTRY_CLASS_ANNOS)) entryPoints.add(c)
                            if (c.isCompanionObj()) c.containingClassOrObject?.let { addEdge(it, c) }
                        }
                        super.visitClassOrObject(c)
                    }

                    override fun visitNamedFunction(f: KtNamedFunction) {
                        if (!f.isLocal && f.name != null) {
                            decls.add(f)
                            if ((f.isTopLevel && f.name == "main") || f.hasAnno(ENTRY_CALLABLE_ANNOS)) entryPoints.add(f)
                        }
                        super.visitNamedFunction(f)
                    }

                    override fun visitProperty(p: KtProperty) {
                        if (!p.isLocal && p.name != null) {
                            decls.add(p)
                            if (p.hasAnno(ENTRY_CALLABLE_ANNOS)) entryPoints.add(p)
                        }
                        super.visitProperty(p)
                    }
                },
            )
        }

        fun KaSession.toLocalDecl(sym: KaSymbol): KtDeclaration? {
            val psi = sym.psi
            if (psi is KtDeclaration && psi in decls) return psi
            return when (sym) {
                is KaPropertyAccessorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
                is KaConstructorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
                else -> (psi as? KtParameter)?.parents?.filterIsInstance<KtClassOrObject>()?.firstOrNull()
            }?.takeIf { it in decls }
        }

        fun enclosingDecl(el: PsiElement): KtDeclaration? {
            var cur: PsiElement? = el
            while (cur != null) {
                if (cur is KtDeclaration && cur in decls) return cur
                if (cur is KtConstructor<*>) return cur.getContainingClassOrObject().takeIf { it in decls }
                cur = cur.parent
            }
            return null
        }

        // Pass 2: Resolve references and override hierarchies with K2 Analysis API
        val resolveMs = measureTimeMillis {
            for (file in ktFiles) {
                analyze(file) {
                    file.accept(
                        object : KtTreeVisitorVoid() {
                            override fun visitImportDirective(d: KtImportDirective) {}
                            override fun visitPackageDirective(d: KtPackageDirective) {}

                            override fun visitElement(el: PsiElement) {
                                if (el is KtDeclaration && el in decls) {
                                    if (el is KtNamedFunction || el is KtProperty) {
                                        (el.symbol as? KaCallableSymbol)?.allOverriddenSymbols?.forEach { ov ->
                                            val localOv = toLocalDecl(ov)
                                            if (localOv != null) addEdge(localOv, el)
                                            else el.containingClassOrObject?.takeIf { it in decls }?.let { addEdge(it, el) }
                                        }
                                    }
                                    if (el is KtClassOrObject) {
                                        el.superTypeListEntries.forEach { sup ->
                                            if (listOf("ImplBase", "Api", "Filter", "SpannerRepository").any { sup.text.contains(it) }) {
                                                entryPoints.add(el)
                                            }
                                        }
                                    }
                                }

                                val owner = enclosingDecl(el)
                                for (ref in el.references.filterIsInstance<KtReference>()) {
                                    val targets = runCatching { ref.resolveToSymbols() }.getOrDefault(emptyList())
                                    for (sym in targets) {
                                        val target = toLocalDecl(sym) ?: continue
                                        if (owner != null) addEdge(owner, target)
                                        target.containingClassOrObject?.takeIf { it in decls }?.let { addEdge(target, it) }
                                    }
                                }
                                super.visitElement(el)
                            }
                        },
                    )
                }
            }
        }

        // Pass 3: BFS reachability from entry points
        val reachable = mutableSetOf<KtDeclaration>()
        val q = ArrayDeque<KtDeclaration>()
        entryPoints.forEach { if (reachable.add(it)) q.add(it) }
        while (q.isNotEmpty()) {
            for (next in edges[q.removeFirst()].orEmpty()) if (reachable.add(next)) q.add(next)
        }

        val dead = decls.filter { d ->
            if (d in reachable || d.containingKtFile.virtualFilePath.contains("/generated/")) return@filter false
            val parent = d.containingClassOrObject
            if (parent != null && parent in decls && parent !in reachable) return@filter false
            when (d) {
                is KtObjectDeclaration -> !d.isCompanion()
                is KtNamedFunction -> d.name !in OBJECT_METHODS
                is KtProperty -> (parent as? KtClass)?.isData() != true
                else -> true
            }
        }

        val (deadClasses, deadMembers) = dead.partition { it is KtClassOrObject }
        println("Analysis API Dead Code Report (src/main only): ${ktFiles.size} files, ${decls.size} decls | ${sessionMs + resolveMs} ms (session=${sessionMs}ms, resolve=${resolveMs}ms)")
        println("Unreachable classes (${deadClasses.size}):")
        deadClasses.sortedBy { it.fqLabel() }.forEach { println("  ${it.fqLabel()} (${File(it.containingKtFile.virtualFilePath).name}:${it.lineNum()})") }
        println("Unreachable functions/properties (${deadMembers.size}):")
        deadMembers.sortedBy { it.fqLabel() }.forEach { println("  ${it.fqLabel()} (${File(it.containingKtFile.virtualFilePath).name}:${it.lineNum()})") }
    } finally {
        Disposer.dispose(disposable)
    }
    exitProcess(0)
}
