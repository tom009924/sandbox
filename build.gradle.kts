plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.dependency.analysis)
}

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.file.RegularFile
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.nio.file.Files

allprojects {
    group = "com.example"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withType<JavaBasePlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configurations.matching {
            it.name.startsWith("kotlin") || it.name.startsWith("swiftExport")
        }.configureEach {
            resolutionStrategy.eachDependency {
                when {
                    requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-reflect" ->
                        useVersion(rootProject.libs.versions.kotlin.get())
                    requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines") ->
                        useVersion("1.10.2")
                    requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization") ->
                        useVersion("1.9.0")
                }
            }
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<DetektExtension> {
            autoCorrect = findProperty("detektAutoCorrect")?.toString()?.toBooleanStrictOrNull() ?: true
            val candidateDirs = listOf(
                "src/main/kotlin",
                "src/test/kotlin",
                "src/integrationTest/kotlin",
            ).map { file(it) }.filter { it.exists() }
            source.setFrom(candidateDirs)
        }
        configurations.matching { it.name.startsWith("detekt") }.configureEach {
            resolutionStrategy.eachDependency {
                when {
                    requested.group == "org.jetbrains.kotlin" -> useVersion("2.0.21")
                    requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines") ->
                        useVersion("1.10.2")
                    requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization") ->
                        useVersion("1.9.0")
                    requested.group == "org.slf4j" && requested.name == "slf4j-api" ->
                        useVersion("2.0.20")
                }
            }
        }
    }

    plugins.withId("io.spring.dependency-management") {
        configurations.all {
            resolutionStrategy.eachDependency {
                when {
                    requested.group == "org.apache.httpcomponents.client5" -> useVersion("5.6.3")
                    requested.group == "org.apache.httpcomponents.core5" -> useVersion("5.4.3")
                    requested.group.startsWith("org.bouncycastle") -> useVersion("1.85")
                    requested.group == "com.google.guava" && requested.name == "guava" -> useVersion("33.7.2-jre")
                }
            }
        }

        dependencies {
            constraints {
                add("implementation", rootProject.libs.bouncycastle.bcprov) {
                    because("CVE-2025-14813, CVE-2026-5598 and GHSA-9pwp-9qqc-pr26 fixed in 1.85")
                }
                add("implementation", "org.bouncycastle:bcpg-jdk18on:1.85") {
                    because("fix GHSA-cj8j-37rh-8475")
                }
                add("implementation", "org.bouncycastle:bcpkix-jdk18on:1.85") {
                    because("fix GHSA-wg6q-6289-32hp")
                }
                add("implementation", "com.google.guava:guava:33.7.2-jre") {
                    because("fix GHSA-xxph-c9ww-hj94")
                }
                add("implementation", "com.github.jknack:handlebars:4.5.2") {
                    because("fix GHSA-r4gv-qr8j-p3pg")
                }
                add("implementation", "commons-fileupload:commons-fileupload:1.6.0") {
                    because("fix GHSA-vv7r-c36w-3prj")
                }
                add("implementation", "commons-io:commons-io:2.18.0") {
                    because("fix GHSA-78wr-2p64-hpwj")
                }
                add("implementation", "org.apache.httpcomponents.client5:httpclient5:5.6.3") {
                    because("fix GHSA-hjcp-jmpx-g3qm")
                }
                add("implementation", "org.apache.httpcomponents.core5:httpcore5:5.4.3") {
                    because("fix GHSA-hf6x-8p5f-cgmf")
                }
                add("implementation", "org.apache.httpcomponents.core5:httpcore5-h2:5.4.3") {
                    because("fix GHSA-v3jc-474w-2wm6")
                }
            }
        }
    }

    plugins.withId("org.springframework.boot") {
        tasks.named<BootJar>("bootJar") {
            archiveFileName.set("app.jar")
        }

        tasks.named<Jar>("jar") {
            enabled = false
        }

        tasks.withType<Test>().configureEach {
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
    }

    plugins.withId("jacoco") {
        extensions.configure<JacocoPluginExtension> {
            toolVersion = rootProject.libs.versions.jacoco.get()
        }
    }

    plugins.withId("org.cyclonedx.bom") {
        tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
            includeConfigs.set(listOf("runtimeClasspath"))
            xmlOutput.convention(null as RegularFile?)
        }
        tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
            jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
            xmlOutput.convention(null as RegularFile?)
        }
    }
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
            onUsedTransitiveDependencies {
                severity("ignore")
            }
            onUnusedDependencies {
                exclude(
                    "com.google.cloud:spring-cloud-gcp-starter-data-spanner",
                    "org.springframework.boot:spring-boot-starter-security",
                    "org.springframework.boot:spring-boot-starter-validation",
                    "org.springframework.boot:spring-boot-starter-web",
                    "org.springframework.boot:spring-boot-starter-actuator",
                    "org.springframework.boot:spring-boot-starter-flyway",
                    "org.springframework.boot:spring-boot-starter-test",
                    "org.springframework.boot:spring-boot-starter-webmvc-test",
                    "com.tngtech.archunit:archunit-junit5",
                )
            }
            onIncorrectConfiguration {
                exclude(
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "io.rest-assured:rest-assured",
                )
            }
        }
    }
}

val nvmBinDir = "/usr/local/share/nvm/current/bin"
val npmExecutable = if (file("$nvmBinDir/npm").exists() && !System.getenv("PATH").orEmpty().contains(nvmBinDir)) {
    "$nvmBinDir/npm"
} else {
    "npm"
}

fun Exec.setupNodeEnvironment() {
    if (file(nvmBinDir).exists() && !System.getenv("PATH").orEmpty().contains(nvmBinDir)) {
        val currentPath = System.getenv("PATH").orEmpty()
        environment("PATH", if (currentPath.isEmpty()) nvmBinDir else "$nvmBinDir:$currentPath")
    }
    environment("REDOCLY_SUPPRESS_UPDATE_NOTICE", "true")
    environment("REDOCLY_TELEMETRY", "off")
}

val npmInstall = tasks.register<Exec>("npmInstall") {
    description = "Installs Node dependencies using npm ci."
    group = "build setup"
    setupNodeEnvironment()
    commandLine(npmExecutable, "ci")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
    onlyIf {
        val nodeModulesDir = file("node_modules")
        if (nodeModulesDir.exists()) {
            false
        } else {
            val candidateRoots = listOf(
                file("/workspaces/sandbox/node_modules"),
                file("/workspaces/kotlin-devcontainer/node_modules"),
            )
            val sharedNodeModules = candidateRoots.firstOrNull { it.exists() && it.isDirectory }
            if (sharedNodeModules != null) {
                try {
                    Files.createSymbolicLink(
                        nodeModulesDir.toPath(),
                        sharedNodeModules.toPath(),
                    )
                    logger.lifecycle("Linked shared node_modules from ${sharedNodeModules.absolutePath}")
                    false
                } catch (e: Exception) {
                    logger.warn("Could not symlink shared node_modules: ${e.message}")
                    true
                }
            } else {
                true
            }
        }
    }
}

val bundleOpenApi = tasks.register<Exec>("bundleOpenApi") {
    description = "Bundles public and internal OpenAPI specifications using OpenAPI Overlays via Redocly CLI."
    group = "documentation"
    dependsOn(npmInstall)
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "bundle:openapi")
    inputs.file("docs/openapi.yaml")
    inputs.dir("docs/paths")
    inputs.dir("docs/components")
    inputs.file("redocly.yaml")
    inputs.file("docs/overlays/admin.yaml")
    inputs.file("docs/overlays/ja.yaml")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.file("docs/dist/openapi.yaml")
    outputs.file("docs/dist/openapi.admin.yaml")
    outputs.file("docs/dist/openapi.ja.yaml")
}

tasks.register("bundleOpenApiJa") {
    description = "Alias for bundleOpenApi for backwards compatibility."
    dependsOn(bundleOpenApi)
}

val lintOpenApi = tasks.register<Exec>("lintOpenApi") {
    description = "Lints OpenAPI specifications and Arazzo workflows using Redocly CLI."
    group = "verification"
    dependsOn(npmInstall, bundleOpenApi)
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "lint:openapi:check")
    inputs.file("docs/openapi.yaml")
    inputs.dir("docs/paths")
    inputs.dir("docs/components")
    inputs.file("redocly.yaml")
    inputs.file("docs/overlays/admin.yaml")
    inputs.file("docs/overlays/ja.yaml")
    inputs.dir("docs/workflows")
    inputs.dir("docs/models")
    inputs.file("package.json")
    inputs.file("package-lock.json")
}

val generateDmnModel = tasks.register<Exec>("generateDmnModel") {
    description = "Generates Kotlin domain decision model code from OMG DMN 1.3 models."
    group = "code generation"
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "generate:dmn-kotlin")
    inputs.file("docs/models/todo-decisions.dmn")
    inputs.file("scripts/generate-dmn-kotlin.mjs")
    inputs.file("scripts/check-dmn-model.mjs")
    outputs.dir("services/todo-service/build/generated/dmn")
}

val lintAsyncApi = tasks.register<Exec>("lintAsyncApi") {
    description = "Lints AsyncAPI specification using Redocly CLI."
    group = "verification"
    dependsOn(npmInstall)
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "lint:asyncapi")
    inputs.file("docs/asyncapi.yaml")
    inputs.file("redocly.yaml")
    inputs.file("package.json")
    inputs.file("package-lock.json")
}

// Root convenience aggregate tasks
tasks.register("detekt") {
    description = "Runs Detekt across all Kotlin modules."
    group = "verification"
    dependsOn(
        ":libs:grpc-common:detekt",
        ":services:api-gateway:detekt",
        ":services:identity-service:detekt",
        ":services:todo-service:detekt",
        ":integration-tests:detekt",
    )
}

tasks.register("test") {
    description = "Runs unit tests across all modules."
    group = "verification"
    dependsOn(
        ":libs:grpc-common:test",
        ":services:api-gateway:test",
        ":services:identity-service:test",
        ":services:todo-service:test",
    )
}

tasks.register("bootJar") {
    description = "Builds executable Spring Boot JARs for all services."
    group = "build"
    dependsOn(
        ":services:api-gateway:bootJar",
        ":services:identity-service:bootJar",
        ":services:todo-service:bootJar",
    )
}

tasks.register("cyclonedxBom") {
    description = "Generates CycloneDX SBOMs for all services."
    group = "reporting"
    dependsOn(
        ":services:api-gateway:cyclonedxBom",
        ":services:identity-service:cyclonedxBom",
        ":services:todo-service:cyclonedxBom",
    )
}

tasks.register("openApiGenerate") {
    description = "Generates OpenAPI server interfaces and models across services."
    group = "openapi tools"
    dependsOn(
        ":services:api-gateway:openApiGenerate",
        ":services:todo-service:openApiGenerate",
    )
}

tasks.register("integrationTest") {
    description = "Runs integration tests for todo-service."
    group = "verification"
    dependsOn(":services:todo-service:integrationTest")
}

tasks.register("containerIntegrationTest") {
    description = "Runs cross-service container integration tests against running Docker Compose services."
    group = "verification"
    dependsOn(":integration-tests:test")
}

tasks.register("propertyTest") {
    description = "Runs property-based tests for todo-service."
    group = "verification"
    dependsOn(":services:todo-service:propertyTest")
}

tasks.register("generateExternalCallDiagrams") {
    description = "Generates Mermaid external call sequence diagrams for todo-service."
    group = "documentation"
    dependsOn(":services:todo-service:generateExternalCallDiagrams")
}

tasks.register("bootRun") {
    description = "Runs todo-service locally."
    group = "application"
    dependsOn(":services:todo-service:bootRun")
}

tasks.register("check") {
    description = "Runs all verification tasks across the monorepo."
    group = "verification"
    dependsOn(
        "buildHealth",
        lintOpenApi,
        lintAsyncApi,
        ":libs:grpc-common:check",
        ":services:api-gateway:check",
        ":services:identity-service:check",
        ":services:todo-service:check",
    )
}
