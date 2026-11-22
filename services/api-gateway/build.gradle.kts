plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = "1.0.0"

dependencyLocking {
    lockAllConfigurations()
}

detekt {
    autoCorrect = findProperty("detektAutoCorrect")?.toString()?.toBooleanStrictOrNull() ?: true
    config.setFrom(rootProject.files("detekt.yml").takeIf { it.singleFile.exists() } ?: emptyList<Any>())
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
        ),
    )
}

configurations.matching { it.name.startsWith("detekt") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
        }
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "org.apache.httpcomponents.client5" -> useVersion("5.6.3")
            requested.group == "org.apache.httpcomponents.core5" -> useVersion("5.4.3")
            requested.group.startsWith("org.bouncycastle") -> useVersion("1.85")
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because("CVE-2025-14813, CVE-2026-5598 and GHSA-9pwp-9qqc-pr26 fixed in 1.85")
        }
        implementation("com.google.guava:guava:33.7.2-jre") {
            because("fix GHSA-xxph-c9ww-hj94")
        }
        implementation("commons-io:commons-io:2.18.0") {
            because("fix GHSA-78wr-2p64-hpwj")
        }
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.3") {
            because("fix GHSA-hjcp-jmpx-g3qm")
        }
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3") {
            because("fix GHSA-hf6x-8p5f-cgmf")
        }
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3") {
            because("fix GHSA-v3jc-474w-2wm6")
        }
    }

    implementation(project(":libs:identity-proto"))
    implementation(project(":libs:todo-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.jackson.annotations)
    runtimeOnly(libs.jackson.module.kotlin)
    runtimeOnly(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.grpc.inprocess)
    detektPlugins(libs.detekt.formatting)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "256m"
    maxParallelForks = 1
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("${rootProject.projectDir}/docs/dist/openapi.admin.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").map { it.asFile.absolutePath })
    apiPackage.set("com.example.gateway.presentation.api")
    modelPackage.set("com.example.gateway.presentation.model")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "exceptionHandler" to "false",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "useBeanValidation" to "true",
            "serializationLibrary" to "jackson",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
}

tasks.openApiGenerate {
    mustRunAfter(rootProject.tasks.named("bundleOpenApi"))
}

sourceSets.main {
    kotlin.srcDir(tasks.openApiGenerate.map { file("${it.outputDir.get()}/src/main/kotlin") })
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}
