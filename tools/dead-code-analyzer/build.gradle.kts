plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi")
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.analysis.api.KaImplementationDetail")
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.analysis.api.KaNonPublicApi")
    }
}

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

val kotlinVersion = "2.2.21"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")
    listOf(
        "analysis-api-standalone-for-ide",
        "analysis-api-for-ide",
        "analysis-api-k2-for-ide",
        "analysis-api-impl-base-for-ide",
        "low-level-api-fir-for-ide",
        "analysis-api-platform-interface-for-ide",
        "symbol-light-classes-for-ide",
    ).forEach { implementation("org.jetbrains.kotlin:$it:$kotlinVersion") { isTransitive = false } }
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass.set("com.example.deadcode.analysisapi.AnalysisApiDeadCodeAnalyzerKt")
    applicationDefaultJvmArgs = listOf("-Xmx768m")
}
