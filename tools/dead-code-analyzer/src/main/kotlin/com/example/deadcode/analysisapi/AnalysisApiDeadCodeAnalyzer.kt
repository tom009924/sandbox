package com.example.deadcode.analysisapi

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyAccessorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis

data class Finding(
    val key: String,
    val location: String,
)

data class AnalysisReport(
    val scannedFiles: Int,
    val declaredCount: Int,
    val deadClasses: List<Finding>,
    val deadMembers: List<Finding>,
    val elapsedMs: Long,
) {
    val findings: List<Finding> get() = deadClasses + deadMembers
}

class AnalysisApiDeadCodeAnalyzer {
    fun analyze(metadata: Metadata): AnalysisReport {
        val disposable = Disposer.newDisposable("DeadCodeAnalysis")
        try {
            lateinit var report: AnalysisReport
            val elapsedMs = measureTimeMillis {
                val session = buildSession(disposable, metadata)
                val ktFiles = session.modulesWithFiles.entries
                    .flatMap { it.value.filterIsInstance<KtFile>() }

                val state = ScanState()
                collectDeclarations(ktFiles, state)
                resolveReferences(ktFiles, state)
                propagateDiBeans(state)
                resolveBeanFactories(state)
                propagateOverrides(state)

                val (deadClasses, deadMembers) = buildFindings(state)
                report = AnalysisReport(
                    scannedFiles = ktFiles.size,
                    declaredCount = state.declared.size,
                    deadClasses = deadClasses,
                    deadMembers = deadMembers,
                    elapsedMs = 0,
                )
            }
            return report.copy(elapsedMs = elapsedMs)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun buildSession(disposable: Disposable, metadata: Metadata): StandaloneAnalysisAPISession {
        val platform = JvmPlatforms.defaultJvmPlatform
        return buildStandaloneAnalysisAPISession(projectDisposable = disposable) {
            buildKtModuleProvider {
                this.platform = platform
                val jdk = addModule(
                    buildKtSdkModule {
                        this.platform = platform
                        libraryName = "JDK"
                        addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                    },
                )
                val cpPaths = metadata.classpath.map(Path::of).filter { it.exists() }
                val libs = if (cpPaths.isNotEmpty()) {
                    addModule(
                        buildKtLibraryModule {
                            this.platform = platform
                            libraryName = "classpath"
                            cpPaths.forEach(::addBinaryRoot)
                        },
                    )
                } else {
                    null
                }
                addModule(
                    buildKtSourceModule {
                        this.platform = platform
                        moduleName = "sandbox-main"
                        metadata.sourceRoots.map(Path::of).filter { it.exists() }.forEach(::addSourceRoot)
                        addRegularDependency(jdk)
                        if (libs != null) addRegularDependency(libs)
                    },
                )
            }
        }
    }

    private fun collectDeclarations(ktFiles: List<KtFile>, state: ScanState) {
        for (file in ktFiles) {
            if (file.virtualFilePath.contains("/generated/")) continue
            file.accept(
                object : KtTreeVisitorVoid() {
                    override fun visitDeclaration(d: KtDeclaration) {
                        val isTarget = (d is KtClassOrObject && d !is KtEnumEntry && !d.isLocal) ||
                            (d is KtNamedFunction && !d.isLocal && d.name != null) ||
                            (d is KtProperty && !d.isLocal && d.name != null)
                        if (isTarget) {
                            state.declared.add(d)
                            when {
                                d is KtClassOrObject && (d.hasAnno(ENTRY_CLASS_ANNOS) ||
                                    d.superTypeListEntries.any { s -> FRAMEWORK_SUPERS.any(s.text::contains) }) ->
                                    state.referenced.add(d)
                                d is KtClassOrObject && d.hasAnno(DI_BEAN_ANNOS) -> state.diBeans.add(d)
                                d is KtNamedFunction && ((d.isTopLevel && d.name == "main") || d.hasAnno(ENTRY_METHOD_ANNOS)) -> {
                                    state.referenced.add(d)
                                    d.containingClassOrObject?.let(state.referenced::add)
                                }
                                d is KtNamedFunction && d.hasAnno(BEAN_ANNO) ->
                                    d.typeReference?.let(state.beanMethodReturnTypeRefs::add)
                            }
                        }
                        super.visitDeclaration(d)
                    }
                },
            )
        }
    }

    private fun resolveReferences(ktFiles: List<KtFile>, state: ScanState) {
        for (file in ktFiles) {
            analyze(file) {
                file.accept(
                    object : KtTreeVisitorVoid() {
                        override fun visitImportDirective(d: KtImportDirective) {}
                        override fun visitPackageDirective(d: KtPackageDirective) {}

                        override fun visitSuperTypeListEntry(specifier: KtSuperTypeListEntry) {
                            // Do not mark supertypes as "referenced" merely from implements/extends;
                            // inheritance edges are collected via KaClassSymbol.superTypes below.
                        }

                        override fun visitElement(el: PsiElement) {
                            if (el in state.beanMethodReturnTypeRefs) return
                            if (el is KtClassOrObject && el in state.declared) {
                                (el.symbol as? KaClassSymbol)?.superTypes?.forEach { supType ->
                                    val supPsi = (supType as? KaClassType)?.symbol?.psi as? KtDeclaration
                                    if (supPsi != null && supPsi in state.declared) {
                                        state.subtypes.computeIfAbsent(supPsi) { mutableSetOf() }.add(el)
                                    }
                                }
                            }
                            if (el is KtNamedFunction && el in state.declared && el.hasAnno(BEAN_ANNO)) {
                                val retCls = ((el.symbol as? KaCallableSymbol)?.returnType as? KaClassType)
                                    ?.symbol?.psi as? KtClassOrObject
                                state.beanMethods[el] = retCls?.takeIf { it in state.declared }
                            }
                            if (el in state.declared && (el is KtNamedFunction || el is KtProperty)) {
                                ((el as KtDeclaration).symbol as? KaCallableSymbol)?.allOverriddenSymbols?.forEach { ov ->
                                    val base = toLocal(ov, state)
                                    if (base != null) state.overridesByBase.computeIfAbsent(base) { mutableSetOf() }.add(el)
                                    else state.referenced.add(el)
                                }
                            }
                            for (ref in el.references.filterIsInstance<KtReference>()) {
                                runCatching { ref.resolveToSymbols() }.getOrDefault(emptyList()).forEach { sym ->
                                    val target = toLocal(sym, state) ?: return@forEach
                                    state.referenced.add(target)
                                    target.containingClassOrObject?.takeIf { it in state.declared }?.let(state.referenced::add)
                                }
                            }
                            super.visitElement(el)
                        }
                    },
                )
            }
        }
    }

    private fun KaSession.toLocal(sym: KaSymbol, state: ScanState): KtDeclaration? {
        val psi = sym.psi
        if (psi is KtDeclaration && psi in state.declared) return psi
        return when (sym) {
            is KaPropertyAccessorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
            is KaConstructorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
            else -> (psi as? KtParameter)?.parents?.filterIsInstance<KtClassOrObject>()?.firstOrNull()
        }?.takeIf { it in state.declared }
    }

    private fun propagateDiBeans(state: ScanState) {
        val queue = ArrayDeque(state.referenced.toList())
        while (queue.isNotEmpty()) {
            for (sub in state.subtypes[queue.removeFirst()].orEmpty()) {
                if (sub in state.diBeans && state.referenced.add(sub)) queue.add(sub)
            }
        }
    }

    private fun resolveBeanFactories(state: ScanState) {
        state.beanMethods.forEach { (fn, retCls) ->
            if (retCls == null || retCls in state.referenced) state.referenced.add(fn)
        }
    }

    private fun propagateOverrides(state: ScanState) {
        state.overridesByBase.forEach { (base, impls) ->
            if (base in state.referenced) state.referenced.addAll(impls)
        }
    }

    private fun buildFindings(state: ScanState): Pair<List<Finding>, List<Finding>> {
        val dead = state.declared.filter { d ->
            if (d in state.referenced) return@filter false
            val parent = d.containingClassOrObject
            if (parent != null && parent in state.declared && parent !in state.referenced) return@filter false
            when (d) {
                is KtObjectDeclaration -> !d.isCompanion()
                is KtNamedFunction -> d.name !in OBJECT_METHODS
                is KtProperty -> (parent as? KtClass)?.isData() != true
                else -> true
            }
        }
        val (classes, members) = dead.partition { it is KtClassOrObject }
        val deadClasses = classes.sortedBy { it.fqLabel() }.map {
            val tag = if (it in state.diBeans) " [unused bean]" else ""
            Finding("class:${it.fqLabel()}$tag", it.sourceLocation())
        }
        val deadMembers = members.sortedBy { it.fqLabel() }.map {
            val tag = if (it in state.beanMethods) " [unused @Bean]" else ""
            Finding("member:${it.fqLabel()}$tag", it.sourceLocation())
        }
        return deadClasses to deadMembers
    }

    private class ScanState {
        val declared = linkedSetOf<KtDeclaration>()
        val referenced = mutableSetOf<KtDeclaration>()
        val diBeans = mutableSetOf<KtClassOrObject>()
        val beanMethods = mutableMapOf<KtNamedFunction, KtClassOrObject?>()
        val beanMethodReturnTypeRefs = mutableSetOf<PsiElement>()
        val subtypes = mutableMapOf<KtDeclaration, MutableSet<KtClassOrObject>>()
        val overridesByBase = mutableMapOf<KtDeclaration, MutableSet<KtDeclaration>>()
    }

    companion object {
        private val BEAN_ANNO = setOf("Bean")
        private val OBJECT_METHODS = setOf("toString", "hashCode", "equals")
        private val FRAMEWORK_SUPERS = listOf("ImplBase", "Api", "Filter", "SpannerRepository")

        private val ENTRY_CLASS_ANNOS = setOf(
            "SpringBootApplication", "Controller", "RestController",
            "ControllerAdvice", "RestControllerAdvice", "Configuration", "Table",
        )
        private val DI_BEAN_ANNOS = setOf("Component", "Service", "Repository", "ConfigurationProperties")
        private val ENTRY_METHOD_ANNOS = setOf(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping",
            "DeleteMapping", "ExceptionHandler", "Scheduled", "EventListener",
            "PostConstruct", "PreDestroy", "JsonCreator", "JsonValue",
        )

        private fun KtDeclaration.hasAnno(names: Set<String>) =
            annotationEntries.any { it.shortName?.asString() in names }

        private fun KtDeclaration.fqLabel(): String {
            val pkg = containingKtFile.packageFqName.asString()
            val owners = parents.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().reversed()
            val suffix = if (this is KtNamedFunction) "()" else ""
            return (listOfNotNull(pkg.takeIf { it.isNotEmpty() }) + owners + (name ?: "<anon>")).joinToString(".") + suffix
        }

        private fun KtDeclaration.sourceLocation(): String {
            val fileName = File(containingKtFile.virtualFilePath).name
            val line = containingFile.viewProvider.document?.getLineNumber(textOffset)?.plus(1) ?: 0
            return "$fileName:$line"
        }
    }
}
