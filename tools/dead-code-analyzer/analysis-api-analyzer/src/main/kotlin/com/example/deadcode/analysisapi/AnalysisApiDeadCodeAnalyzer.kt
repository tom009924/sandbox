package com.example.deadcode.analysisapi

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
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
import org.jetbrains.kotlin.lexer.KtTokens
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

enum class DeclKind {
    CLASS,
    OBJECT,
    FUNCTION,
    PROPERTY,
}

data class DeclNode(
    val id: String,
    val modulePath: String,
    val kind: DeclKind,
    val displayName: String,
    val filePath: String,
    val line: Int,
    val isGenerated: Boolean,
    val isDataClassProperty: Boolean = false,
    val isEntryPoint: Boolean = false,
    val parentId: String? = null,
)

private val ENTRY_POINT_CLASS_ANNOTATIONS = setOf(
    "SpringBootApplication",
    "Component",
    "Service",
    "Repository",
    "Controller",
    "RestController",
    "ControllerAdvice",
    "RestControllerAdvice",
    "Configuration",
    "ConfigurationProperties",
    "Table",
)

private val ENTRY_POINT_CALLABLE_ANNOTATIONS = setOf(
    "Bean",
    "RequestMapping",
    "GetMapping",
    "PostMapping",
    "PutMapping",
    "PatchMapping",
    "DeleteMapping",
    "ExceptionHandler",
    "Scheduled",
    "EventListener",
    "PostConstruct",
    "PreDestroy",
    "JsonCreator",
    "JsonValue",
)

private val OBJECT_METHOD_NAMES = setOf("toString", "hashCode", "equals")

private fun lineNumberOf(element: PsiElement): Int {
    val file = element.containingFile ?: return 0
    val doc = file.viewProvider.document ?: return 0
    return doc.getLineNumber(element.textOffset) + 1
}

private fun KtDeclaration.hasAnyAnnotationShortName(targets: Set<String>): Boolean =
    annotationEntries.any { it.shortName?.asString() in targets }

private fun declDisplayName(decl: KtDeclaration): String {
    val pkg = decl.containingKtFile.packageFqName.asString()
    val enclosing = decl.parents
        .filterIsInstance<KtClassOrObject>()
        .mapNotNull { it.name ?: if (it is KtObjectDeclaration && it.isCompanion()) "Companion" else null }
        .toList()
        .reversed()
    val ownName = decl.name ?: when {
        decl is KtObjectDeclaration && decl.isCompanion() -> "Companion"
        else -> "<anonymous>"
    }
    val prefix = (listOfNotNull(pkg.takeIf { it.isNotEmpty() }) + enclosing).joinToString(".")
    return if (prefix.isEmpty()) ownName else "$prefix.$ownName"
}

private fun declUniqueId(modulePath: String, decl: KtDeclaration): String {
    val filePath = decl.containingKtFile.virtualFilePath
    return "$modulePath::$filePath::${decl.textOffset}::${decl::class.simpleName}::${decl.name.orEmpty()}"
}

fun main(args: Array<String>) {
    val metadataPath = args.firstOrNull()
        ?: error("Usage: AnalysisApiDeadCodeAnalyzer <path-to-sandbox-metadata.json>")
    val metadata = Json.decodeFromString<SandboxMetadata>(File(metadataPath).readText())

    val disposable = Disposer.newDisposable("DeadCodeAnalysisSession")
    try {
        val platform = JvmPlatforms.defaultJvmPlatform
        val sourceModulesByPath = linkedMapOf<String, KaSourceModule>()
        val generatedRootPrefixes = metadata.modules.flatMap { it.generatedSourceRoots }.toSet()

        var sessionBuildMs = 0L
        val session = run {
            var built: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession? = null
            sessionBuildMs = measureTimeMillis {
                built = buildStandaloneAnalysisAPISession(projectDisposable = disposable) {
                    buildKtModuleProvider {
                        this.platform = platform

                        val jdkModule = addModule(
                            buildKtSdkModule {
                                this.platform = platform
                                libraryName = "JDK"
                                addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                            },
                        )

                        // Cache library modules per jar path to avoid duplicates
                        val libCache = mutableMapOf<String, KaLibraryModule>()
                        fun getOrCreateLib(jarPath: String): KaLibraryModule =
                            libCache.getOrPut(jarPath) {
                                addModule(
                                    buildKtLibraryModule {
                                        this.platform = platform
                                        libraryName = File(jarPath).name
                                        addBinaryRoot(Path.of(jarPath))
                                    },
                                )
                            }

                        // Topological sort modules so dependencies are created first
                        val moduleByPath = metadata.modules.associateBy { it.path }
                        val visited = mutableSetOf<String>()
                        val ordered = mutableListOf<ModuleMetadata>()
                        fun dfs(path: String) {
                            if (!visited.add(path)) return
                            val m = moduleByPath[path] ?: return
                            m.projectDependencies.forEach { dfs(it) }
                            ordered.add(m)
                        }
                        metadata.modules.forEach { dfs(it.path) }

                        // Map project output dirs so we can distinguish internal project deps vs external jars
                        val internalOutputDirs = metadata.modules.flatMap { it.classesDirs }.toSet()

                        for (m in ordered) {
                            val roots = (m.sourceRoots + m.generatedSourceRoots)
                                .map { Path.of(it) }
                                .filter { it.exists() }
                            if (roots.isEmpty()) continue

                            val srcModule = addModule(
                                buildKtSourceModule {
                                    this.platform = platform
                                    moduleName = m.path
                                    roots.forEach { addSourceRoot(it) }
                                    addRegularDependency(jdkModule)

                                    // Depend on already-built internal KaSourceModules
                                    for (depPath in m.projectDependencies) {
                                        val depSource = sourceModulesByPath[depPath]
                                        if (depSource != null) {
                                            addRegularDependency(depSource)
                                        }
                                    }

                                    // External classpath entries (and proto jars whose sources aren't Kotlin)
                                    for (cp in m.compileClasspath) {
                                        if (cp in internalOutputDirs) continue
                                        val cpFile = File(cp)
                                        if (!cpFile.exists()) continue
                                        // Skip jars belonging to internal Kotlin source modules already added as KaSourceModule
                                        val belongsToInternalKotlinModule = sourceModulesByPath.keys.any { depPath ->
                                            val depMod = moduleByPath[depPath] ?: return@any false
                                            depMod.sourceRoots.isNotEmpty() &&
                                                cpFile.canonicalPath.startsWith(depMod.projectDir)
                                        }
                                        if (belongsToInternalKotlinModule) continue
                                        addRegularDependency(getOrCreateLib(cp))
                                    }
                                },
                            )
                            sourceModulesByPath[m.path] = srcModule
                        }
                    }
                }
            }
            built!!
        }

        val allNodes = linkedMapOf<String, DeclNode>()
        val psiToNodeId = IdentityHashMapWrapper()
        val edges = mutableMapOf<String, MutableSet<String>>()
        val fileTopLevelOwner = mutableMapOf<KtFile, String>()
        var ktFileCount = 0

        fun addEdge(from: String, to: String) {
            if (from == to) return
            edges.computeIfAbsent(from) { mutableSetOf() }.add(to)
        }

        val moduleFiles: List<Pair<String, List<KtFile>>> = session.modulesWithFiles
            .entries
            .mapNotNull { (mod, files) ->
                val srcMod = mod as? KaSourceModule ?: return@mapNotNull null
                val ktFiles = files.filterIsInstance<KtFile>()
                srcMod.name to ktFiles
            }

        // Pass 1: Collect all declarations across all production KaSourceModules
        val pass1Ms = measureTimeMillis {
            for ((modPath, ktFiles) in moduleFiles) {
                ktFileCount += ktFiles.size
                for (ktFile in ktFiles) {
                    val filePath = ktFile.virtualFilePath
                    val isGenFile = modPath.endsWith("-proto") ||
                        generatedRootPrefixes.any { filePath.startsWith(it) } ||
                        filePath.contains("/generated/")

                    // Synthetic file root node for top-level initializers if needed
                    val fileNodeId = "$modPath::$filePath::<file>"
                    fileTopLevelOwner[ktFile] = fileNodeId

                    ktFile.accept(
                        object : KtTreeVisitorVoid() {
                            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                                if (classOrObject !is KtEnumEntry && !classOrObject.isLocal) {
                                    val kind = if (classOrObject is KtObjectDeclaration) DeclKind.OBJECT else DeclKind.CLASS
                                    val parentDecl = classOrObject.containingClassOrObject
                                    val parentId = parentDecl?.let { psiToNodeId[it] }
                                    val isEntry = classOrObject.hasAnyAnnotationShortName(ENTRY_POINT_CLASS_ANNOTATIONS)
                                    val id = declUniqueId(modPath, classOrObject)
                                    allNodes[id] = DeclNode(
                                        id = id,
                                        modulePath = modPath,
                                        kind = kind,
                                        displayName = declDisplayName(classOrObject),
                                        filePath = filePath,
                                        line = lineNumberOf(classOrObject),
                                        isGenerated = isGenFile,
                                        isEntryPoint = isEntry,
                                        parentId = parentId,
                                    )
                                    psiToNodeId[classOrObject] = id
                                }
                                super.visitClassOrObject(classOrObject)
                            }

                            override fun visitNamedFunction(function: KtNamedFunction) {
                                if (!function.isLocal && function.name != null) {
                                    val parentDecl = function.containingClassOrObject
                                    val parentId = parentDecl?.let { psiToNodeId[it] }
                                    val isMain = function.isTopLevel && function.name == "main"
                                    val isEntry = isMain ||
                                        function.hasAnyAnnotationShortName(ENTRY_POINT_CALLABLE_ANNOTATIONS)
                                    val id = declUniqueId(modPath, function)
                                    allNodes[id] = DeclNode(
                                        id = id,
                                        modulePath = modPath,
                                        kind = DeclKind.FUNCTION,
                                        displayName = "${declDisplayName(function)}()",
                                        filePath = filePath,
                                        line = lineNumberOf(function),
                                        isGenerated = isGenFile,
                                        isEntryPoint = isEntry,
                                        parentId = parentId,
                                    )
                                    psiToNodeId[function] = id
                                }
                                super.visitNamedFunction(function)
                            }

                            override fun visitProperty(property: KtProperty) {
                                if (!property.isLocal && property.name != null) {
                                    val parentDecl = property.containingClassOrObject
                                    val parentId = parentDecl?.let { psiToNodeId[it] }
                                    val isDataProp = (parentDecl as? KtClass)?.isData() == true
                                    val isEntry = property.hasAnyAnnotationShortName(ENTRY_POINT_CALLABLE_ANNOTATIONS)
                                    val id = declUniqueId(modPath, property)
                                    allNodes[id] = DeclNode(
                                        id = id,
                                        modulePath = modPath,
                                        kind = DeclKind.PROPERTY,
                                        displayName = declDisplayName(property),
                                        filePath = filePath,
                                        line = lineNumberOf(property),
                                        isGenerated = isGenFile,
                                        isDataClassProperty = isDataProp,
                                        isEntryPoint = isEntry,
                                        parentId = parentId,
                                    )
                                    psiToNodeId[property] = id
                                }
                                super.visitProperty(property)
                            }
                        },
                    )
                }
            }
        }

        // Helper: map a resolved KaSymbol to our local DeclNode ID (if it belongs to sandbox src/main)
        fun KaSession.symbolToNodeId(symbol: KaSymbol): String? {
            val psi = symbol.psi
            if (psi != null) {
                psiToNodeId[psi]?.let { return it }
            }
            return when (symbol) {
                is KaPropertyAccessorSymbol -> symbol.containingDeclaration?.psi?.let { psiToNodeId[it] }
                is KaConstructorSymbol -> symbol.containingDeclaration?.psi?.let { psiToNodeId[it] }
                else -> {
                    // Primary constructor val/var parameter -> map to enclosing class
                    if (psi is KtParameter) {
                        psi.parents.filterIsInstance<KtClassOrObject>().firstOrNull()?.let { psiToNodeId[it] }
                    } else {
                        null
                    }
                }
            }
        }

        val entryPoints = mutableSetOf<String>()
        allNodes.values.filter { it.isEntryPoint }.forEach { entryPoints.add(it.id) }

        // Pass 2: Resolve references and override hierarchies using Kotlin Analysis API
        val pass2Ms = measureTimeMillis {
            for ((_, ktFiles) in moduleFiles) {
                for (ktFile in ktFiles) {
                    analyze(ktFile) {
                        ktFile.accept(
                            object : KtTreeVisitorVoid() {
                                override fun visitImportDirective(importDirective: KtImportDirective) {
                                    // Do NOT count import statements as usages!
                                }

                                override fun visitPackageDirective(directive: KtPackageDirective) {
                                    // Do NOT count package declarations as usages!
                                }

                                override fun visitElement(element: PsiElement) {
                                    val ownerId = findEnclosingNodeId(element, psiToNodeId)
                                        ?: fileTopLevelOwner[ktFile]

                                    // Record override relationships & external framework overrides
                                    if (element is KtDeclaration) {
                                        val nodeId = psiToNodeId[element]
                                        if (nodeId != null) {
                                            if (element is KtNamedFunction || element is KtProperty) {
                                                val sym = element.symbol as? KaCallableSymbol
                                                if (sym != null) {
                                                    val overridden = sym.allOverriddenSymbols.toList()
                                                    for (ov in overridden) {
                                                        val ovId = symbolToNodeId(ov)
                                                        if (ovId != null) {
                                                            // Calling supertype method dispatches to overriding implementation
                                                            addEdge(ovId, nodeId)
                                                        } else {
                                                            // Overrides external framework/JDK method (e.g. gRPC ImplBase, Spring Filter, OpenAPI interface)
                                                            val parentId = allNodes[nodeId]?.parentId
                                                            if (parentId != null) {
                                                                addEdge(parentId, nodeId)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            // If class is an entry point (e.g. Spring @Service/@RestController),
                                            // mark its companion / constructors / primary init reachable from the class
                                            if (element is KtClassOrObject) {
                                                if (element.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
                                                    // no-op
                                                }
                                                val superEntries = element.superTypeListEntries
                                                for (supEntry in superEntries) {
                                                    val typeRef = supEntry.typeReference ?: continue
                                                    val resolvedTypes = typeRef.references
                                                        .filterIsInstance<KtReference>()
                                                        .flatMap { it.resolveToSymbols() }
                                                    for (rs in resolvedTypes) {
                                                        val supName = rs.psi?.containingFile?.name.orEmpty()
                                                        // If extending an external framework class (gRPC ImplBase, Spring, OpenAPI)
                                                        val supId = symbolToNodeId(rs)
                                                        if (supId != null) {
                                                            addEdge(nodeId, supId)
                                                        } else if (supEntry.text.contains("ImplBase") ||
                                                            supEntry.text.contains("Api") ||
                                                            supEntry.text.contains("Filter") ||
                                                            supEntry.text.contains("SpannerRepository") ||
                                                            supName.contains("Grpc")
                                                        ) {
                                                            entryPoints.add(nodeId)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Resolve every KtReference on this PSI element
                                    for (ref in element.references) {
                                        if (ref !is KtReference) continue
                                        val resolved = try {
                                            ref.resolveToSymbols()
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                        for (sym in resolved) {
                                            val targetId = symbolToNodeId(sym) ?: continue
                                            if (ownerId != null) {
                                                addEdge(ownerId, targetId)
                                            }
                                            // Using a member also keeps its enclosing class/object alive
                                            allNodes[targetId]?.parentId?.let { parentId ->
                                                addEdge(targetId, parentId)
                                            }
                                        }
                                    }

                                    super.visitElement(element)
                                }
                            },
                        )
                    }
                }
            }
        }

        // Link entry-point classes to their constructors/init-referenced targets via class node,
        // and link top-level properties/companion objects inside reachable classes
        for (node in allNodes.values) {
            if (node.kind == DeclKind.OBJECT && node.displayName.endsWith(".Companion") && node.parentId != null) {
                addEdge(node.parentId, node.id)
            }
        }

        // Pass 3: BFS reachability from entryPoints
        val reachable = mutableSetOf<String>()
        val bfsMs = measureTimeMillis {
            val queue = ArrayDeque<String>()
            for (ep in entryPoints) {
                if (reachable.add(ep)) queue.add(ep)
            }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (next in edges[cur].orEmpty()) {
                    if (reachable.add(next)) queue.add(next)
                }
            }
        }

        val deadClasses = mutableListOf<DeclNode>()
        val deadFunctions = mutableListOf<DeclNode>()
        val deadProperties = mutableListOf<DeclNode>()

        for (node in allNodes.values) {
            if (node.isGenerated) continue
            if (node.id in reachable) continue

            // If parent class itself is unreachable, report only the class (avoid noisy child listings)
            if (node.parentId != null && node.parentId !in reachable) continue

            when (node.kind) {
                DeclKind.CLASS, DeclKind.OBJECT -> {
                    if (!node.displayName.endsWith(".Companion")) {
                        deadClasses.add(node)
                    }
                }
                DeclKind.FUNCTION -> {
                    val shortName = node.displayName.substringAfterLast('.').removeSuffix("()")
                    if (shortName !in OBJECT_METHOD_NAMES) {
                        deadFunctions.add(node)
                    }
                }
                DeclKind.PROPERTY -> {
                    if (!node.isDataClassProperty) {
                        deadProperties.add(node)
                    }
                }
            }
        }

        println("====================================================================")
        println("Kotlin Analysis API Dead Code Analyzer Report (Production / main only)")
        println("====================================================================")
        println("Scanned Kotlin files : $ktFileCount")
        println("Total declarations   : ${allNodes.size}")
        println("Entry points         : ${entryPoints.size}")
        println("Session build time   : ${sessionBuildMs} ms")
        println("Pass 1 (decls) time  : ${pass1Ms} ms")
        println("Pass 2 (refs) time   : ${pass2Ms} ms")
        println("Pass 3 (BFS) time    : ${bfsMs} ms")
        println("Total time           : ${sessionBuildMs + pass1Ms + pass2Ms + bfsMs} ms")
        println("Unreachable classes  : ${deadClasses.size}")
        println("Unreachable functions: ${deadFunctions.size}")
        println("Unreachable props    : ${deadProperties.size}")
        println()

        println("--- Unreachable Classes / Objects (${deadClasses.size}) ---")
        deadClasses.sortedWith(compareBy({ it.modulePath }, { it.displayName })).forEach { n ->
            println("  [${n.modulePath}] ${n.displayName} (${File(n.filePath).name}:${n.line})")
        }
        println()

        println("--- Unreachable Functions in Reachable Scopes (${deadFunctions.size}) ---")
        deadFunctions.sortedWith(compareBy({ it.modulePath }, { it.displayName })).forEach { n ->
            println("  [${n.modulePath}] ${n.displayName} (${File(n.filePath).name}:${n.line})")
        }
        println()

        println("--- Unreachable Properties in Reachable Scopes (${deadProperties.size}) ---")
        deadProperties.sortedWith(compareBy({ it.modulePath }, { it.displayName })).forEach { n ->
            println("  [${n.modulePath}] ${n.displayName} (${File(n.filePath).name}:${n.line})")
        }
    } finally {
        Disposer.dispose(disposable)
    }
    // IntelliJ Core leaves non-daemon worker threads alive in standalone mode
    exitProcess(0)
}

private class IdentityHashMapWrapper {
    private val map = java.util.IdentityHashMap<PsiElement, String>()
    operator fun get(key: PsiElement): String? = map[key]
    operator fun set(key: PsiElement, value: String) {
        map[key] = value
    }
}

private fun findEnclosingNodeId(element: PsiElement, psiToNodeId: IdentityHashMapWrapper): String? {
    var cur: PsiElement? = element
    while (cur != null) {
        if (cur is KtDeclaration) {
            psiToNodeId[cur]?.let { return it }
            // Constructor body / init block -> attribute references to enclosing class
            if (cur is KtConstructor<*>) {
                cur.getContainingClassOrObject().let { cls ->
                    psiToNodeId[cls]?.let { return it }
                }
            }
        }
        cur = cur.parent
    }
    return null
}
