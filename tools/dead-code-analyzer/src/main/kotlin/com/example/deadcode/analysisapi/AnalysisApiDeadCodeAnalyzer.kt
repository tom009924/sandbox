package com.example.deadcode.analysisapi

import com.intellij.openapi.util.Disposer
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

private val FRAMEWORK_ANNOS = setOf(
    "SpringBootApplication", "Component", "Service", "Repository", "Controller",
    "RestController", "ControllerAdvice", "RestControllerAdvice", "Configuration",
    "ConfigurationProperties", "Table", "Bean", "RequestMapping", "GetMapping",
    "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "ExceptionHandler",
    "Scheduled", "EventListener", "PostConstruct", "PreDestroy", "JsonCreator", "JsonValue",
)

private fun KtDeclaration.fqLabel(): String {
    val pkg = containingKtFile.packageFqName.asString()
    val owners = parents.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().reversed()
    val suffix = if (this is KtNamedFunction) "()" else ""
    return (listOfNotNull(pkg.takeIf { it.isNotEmpty() }) + owners + (name ?: "<anon>")).joinToString(".") + suffix
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
                    val jdk = addModule(buildKtSdkModule {
                        this.platform = platform; libraryName = "JDK"
                        addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                    })
                    val libs = addModule(buildKtLibraryModule {
                        this.platform = platform; libraryName = "classpath"
                        meta.classpath.map(Path::of).filter { it.exists() }.forEach(::addBinaryRoot)
                    })
                    addModule(buildKtSourceModule {
                        this.platform = platform; moduleName = "sandbox-main"
                        meta.sourceRoots.map(Path::of).filter { it.exists() }.forEach(::addSourceRoot)
                        addRegularDependency(jdk); addRegularDependency(libs)
                    })
                }
            }
        }

        val ktFiles = session.modulesWithFiles.entries
            .filter { it.key is KaSourceModule }.flatMap { it.value.filterIsInstance<KtFile>() }

        val declared = linkedSetOf<KtDeclaration>()
        val referenced = mutableSetOf<KtDeclaration>()
        val overridesByBase = mutableMapOf<KtDeclaration, MutableSet<KtDeclaration>>()

        for (file in ktFiles) {
            if (file.virtualFilePath.contains("/generated/")) continue
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitDeclaration(d: KtDeclaration) {
                    val isTarget = (d is KtClassOrObject && d !is KtEnumEntry && !d.isLocal) ||
                        (d is KtNamedFunction && !d.isLocal && d.name != null) ||
                        (d is KtProperty && !d.isLocal && d.name != null)
                    if (isTarget) {
                        declared.add(d)
                        val isFramework = d.annotationEntries.any { it.shortName?.asString() in FRAMEWORK_ANNOS } ||
                            (d is KtNamedFunction && d.isTopLevel && d.name == "main") ||
                            (d is KtClassOrObject && d.superTypeListEntries.any { s -> listOf("ImplBase", "Api", "Filter", "SpannerRepository").any(s.text::contains) })
                        if (isFramework) referenced.add(d)
                    }
                    super.visitDeclaration(d)
                }
            })
        }

        fun KaSession.toLocal(sym: KaSymbol): KtDeclaration? {
            val psi = sym.psi
            if (psi is KtDeclaration && psi in declared) return psi
            return when (sym) {
                is KaPropertyAccessorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
                is KaConstructorSymbol -> sym.containingDeclaration?.psi as? KtDeclaration
                else -> (psi as? KtParameter)?.parents?.filterIsInstance<KtClassOrObject>()?.firstOrNull()
            }?.takeIf { it in declared }
        }

        val resolveMs = measureTimeMillis {
            for (file in ktFiles) {
                analyze(file) {
                    file.accept(object : KtTreeVisitorVoid() {
                        override fun visitImportDirective(d: KtImportDirective) {}
                        override fun visitPackageDirective(d: KtPackageDirective) {}
                        override fun visitElement(el: com.intellij.psi.PsiElement) {
                            if (el in declared && (el is KtNamedFunction || el is KtProperty)) {
                                ((el as KtDeclaration).symbol as? KaCallableSymbol)?.allOverriddenSymbols?.forEach { ov ->
                                    val base = toLocal(ov)
                                    if (base != null) overridesByBase.computeIfAbsent(base) { mutableSetOf() }.add(el)
                                    else referenced.add(el) // overrides external framework/JDK method
                                }
                            }
                            for (ref in el.references.filterIsInstance<KtReference>()) {
                                runCatching { ref.resolveToSymbols() }.getOrDefault(emptyList()).forEach { sym ->
                                    val target = toLocal(sym) ?: return@forEach
                                    referenced.add(target)
                                    target.containingClassOrObject?.takeIf { it in declared }?.let(referenced::add)
                                }
                            }
                            super.visitElement(el)
                        }
                    })
                }
            }
        }

        // If a base interface/class method is referenced, its overriding implementations are also referenced
        overridesByBase.forEach { (base, impls) -> if (base in referenced) referenced.addAll(impls) }

        val dead = declared.filter { d ->
            if (d in referenced) return@filter false
            val parent = d.containingClassOrObject
            if (parent != null && parent in declared && parent !in referenced) return@filter false
            when (d) {
                is KtObjectDeclaration -> !d.isCompanion()
                is KtNamedFunction -> d.name !in setOf("toString", "hashCode", "equals")
                is KtProperty -> (parent as? KtClass)?.isData() != true
                else -> true
            }
        }

        val (deadClasses, deadMembers) = dead.partition { it is KtClassOrObject }
        println("Analysis API Dead Code Report (src/main only): ${ktFiles.size} files, ${declared.size} decls | ${sessionMs + resolveMs} ms (session=${sessionMs}ms, resolve=${resolveMs}ms)")
        println("Unreferenced classes (${deadClasses.size}):")
        deadClasses.sortedBy { it.fqLabel() }.forEach { println("  ${it.fqLabel()} (${File(it.containingKtFile.virtualFilePath).name}:${it.containingFile.viewProvider.document?.getLineNumber(it.textOffset)?.plus(1) ?: 0})") }
        println("Unreferenced functions/properties (${deadMembers.size}):")
        deadMembers.sortedBy { it.fqLabel() }.forEach { println("  ${it.fqLabel()} (${File(it.containingKtFile.virtualFilePath).name}:${it.containingFile.viewProvider.document?.getLineNumber(it.textOffset)?.plus(1) ?: 0})") }
    } finally {
        Disposer.dispose(disposable)
    }
    exitProcess(0)
}
