package com.example.deadcode.analysisapi

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
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
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private data class Metadata(val sourceRoots: List<String>, val classpath: List<String>)

private fun readMetadata(file: File): Metadata {
    val sources = mutableListOf<String>()
    val cp = mutableListOf<String>()
    file.readLines().forEach { line ->
        when {
            line.startsWith("S\t") -> sources.add(line.substring(2))
            line.startsWith("C\t") -> cp.add(line.substring(2))
        }
    }
    return Metadata(sources, cp)
}

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

private fun KtDeclaration.hasAnno(names: Set<String>) = annotationEntries.any { it.shortName?.asString() in names }

private fun KtDeclaration.fqLabel(): String {
    val pkg = containingKtFile.packageFqName.asString()
    val owners = parents.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().reversed()
    val suffix = if (this is KtNamedFunction) "()" else ""
    return (listOfNotNull(pkg.takeIf { it.isNotEmpty() }) + owners + (name ?: "<anon>")).joinToString(".") + suffix
}

fun main(args: Array<String>) {
    val metaPath = args.firstOrNull { !it.startsWith("--") } ?: error("Pass metadata JSON path")
    val baselinePath = args.indexOf("--baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val updateBaselinePath = args.indexOf("--update-baseline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val meta = readMetadata(File(metaPath))
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
        val diBeans = mutableSetOf<KtClassOrObject>()
        val beanMethods = mutableMapOf<KtNamedFunction, KtClassOrObject?>() // @Bean -> local return class (null = external)
        val beanMethodReturnTypeRefs = mutableSetOf<com.intellij.psi.PsiElement>()
        val subtypes = mutableMapOf<KtDeclaration, MutableSet<KtClassOrObject>>()
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
                        when {
                            d is KtClassOrObject && (d.hasAnno(ENTRY_CLASS_ANNOS) ||
                                d.superTypeListEntries.any { s -> listOf("ImplBase", "Api", "Filter", "SpannerRepository").any(s.text::contains) }) ->
                                referenced.add(d)
                            d is KtClassOrObject && d.hasAnno(DI_BEAN_ANNOS) -> diBeans.add(d)
                            d is KtNamedFunction && ((d.isTopLevel && d.name == "main") || d.hasAnno(ENTRY_METHOD_ANNOS)) -> {
                                referenced.add(d)
                                d.containingClassOrObject?.let(referenced::add)
                            }
                            d is KtNamedFunction && d.hasAnno(setOf("Bean")) -> {
                                d.typeReference?.let(beanMethodReturnTypeRefs::add)
                            }
                        }
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

                        override fun visitSuperTypeListEntry(specifier: KtSuperTypeListEntry) {
                            // Record inheritance for DI bean resolution without marking the supertype as "referenced"
                            val sub = specifier.parents.filterIsInstance<KtClassOrObject>().firstOrNull()
                            if (sub != null && sub in declared) {
                                specifier.typeReference?.typeElement?.references.orEmpty()
                                    .filterIsInstance<KtReference>()
                                    .flatMap { runCatching { it.resolveToSymbols() }.getOrDefault(emptyList()) }
                                    .mapNotNull { toLocal(it) }
                                    .forEach { sup -> subtypes.computeIfAbsent(sup) { mutableSetOf() }.add(sub) }
                            }
                        }

                        override fun visitElement(el: com.intellij.psi.PsiElement) {
                            if (el in beanMethodReturnTypeRefs) return // don't self-mark @Bean return type
                            if (el is KtNamedFunction && el in declared && el.hasAnno(setOf("Bean"))) {
                                val retCls = ((el.symbol as? KaCallableSymbol)?.returnType as? KaClassType)
                                    ?.symbol?.psi as? KtClassOrObject
                                beanMethods[el] = retCls?.takeIf { it in declared }
                            }
                            if (el in declared && (el is KtNamedFunction || el is KtProperty)) {
                                ((el as KtDeclaration).symbol as? KaCallableSymbol)?.allOverriddenSymbols?.forEach { ov ->
                                    val base = toLocal(ov)
                                    if (base != null) overridesByBase.computeIfAbsent(base) { mutableSetOf() }.add(el)
                                    else referenced.add(el)
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

        // Propagate referenced supertypes/interfaces to implementing DI beans
        val q = ArrayDeque(referenced.toList())
        while (q.isNotEmpty()) {
            for (sub in subtypes[q.removeFirst()].orEmpty()) {
                if (sub in diBeans && referenced.add(sub)) q.add(sub)
            }
        }

        // @Bean methods: alive if returning an external type or a referenced local type
        beanMethods.forEach { (fn, retCls) -> if (retCls == null || retCls in referenced) referenced.add(fn) }

        // Propagate referenced base methods to overriding implementations
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
        val findings = buildList {
            deadClasses.sortedBy { it.fqLabel() }.forEach {
                val tag = if (it in diBeans) " [unused bean]" else ""
                add("class:${it.fqLabel()}$tag" to "${File(it.containingKtFile.virtualFilePath).name}:${it.containingFile.viewProvider.document?.getLineNumber(it.textOffset)?.plus(1) ?: 0}")
            }
            deadMembers.sortedBy { it.fqLabel() }.forEach {
                val tag = if (it in beanMethods) " [unused @Bean]" else ""
                add("member:${it.fqLabel()}$tag" to "${File(it.containingKtFile.virtualFilePath).name}:${it.containingFile.viewProvider.document?.getLineNumber(it.textOffset)?.plus(1) ?: 0}")
            }
        }

        println("Analysis API Dead Code Report (src/main only, Spring Bean-aware): ${ktFiles.size} files, ${declared.size} decls | ${sessionMs + resolveMs} ms")
        println("Total findings: ${findings.size} (${deadClasses.size} classes/beans, ${deadMembers.size} members)")

        var exitCode = 0
        if (updateBaselinePath != null) {
            val out = File(updateBaselinePath)
            out.parentFile?.mkdirs()
            val header = "# Production dead-code baseline (Kotlin Analysis API analyzer). Regenerate with --update-baseline.\n"
            out.writeText(header + findings.joinToString("\n") { it.first } + if (findings.isNotEmpty()) "\n" else "")
            println("Updated baseline (${findings.size} entries) at ${out.path}")
        } else if (baselinePath != null) {
            val baselineFile = File(baselinePath)
            val baseline = if (baselineFile.exists()) {
                baselineFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            } else {
                emptySet()
            }
            val currentKeys = findings.map { it.first }.toSet()
            val newFindings = findings.filterNot { it.first in baseline }
            val staleEntries = baseline.filterNot { it in currentKeys }.sorted()
            if (staleEntries.isNotEmpty()) {
                println("Stale baseline entries (${staleEntries.size} - can be removed):")
                staleEntries.forEach { println("  - $it") }
            }
            if (newFindings.isNotEmpty()) {
                println("NEW production dead code detected (${newFindings.size}):")
                newFindings.forEach { (key, loc) -> println("  + $key ($loc)") }
                exitCode = 1
            } else {
                println("OK: No new production dead code beyond baseline (${baseline.size} baselined).")
            }
        } else {
            findings.forEach { (key, loc) -> println("  $key ($loc)") }
        }
        exitCode
    } finally {
        Disposer.dispose(disposable)
    }.let { code -> exitProcess(code) }
}
