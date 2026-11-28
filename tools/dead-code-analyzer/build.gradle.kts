plugins {
    kotlin("jvm") version "2.2.21"
    application
}

kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
}

application {
    mainClass.set("com.example.deadcode.asm.AsmDeadCodeAnalyzerKt")
}
