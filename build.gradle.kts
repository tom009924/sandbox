plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.openapi.generator)
    jacoco
}

group = "com.example"
version = "1.0.0"

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencyLocking {
    lockAllConfigurations()
}

detekt {
    autoCorrect = true
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/integrationTest/kotlin",
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

configurations.matching { it.name.startsWith("integrationTest") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.groovy") {
            useVersion("4.0.26")
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

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.gcp.spanner)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.jackson.annotations)
    runtimeOnly(libs.spanner.jdbc)
    implementation(libs.google.cloud.pubsub)
    runtimeOnly(libs.jackson.module.kotlin)
    runtimeOnly(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.jackson.module.kotlin)
    add(integrationTestSourceSet.implementationConfigurationName, libs.rest.assured)
    add(integrationTestSourceSet.implementationConfigurationName, libs.swagger.parser)
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
                    "org.springframework.boot:spring-boot-starter-data-jpa",
                    "org.springframework.boot:spring-boot-starter-security",
                    "org.springframework.boot:spring-boot-starter-validation",
                    "org.springframework.boot:spring-boot-starter-web",
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

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    maxParallelForks = 1
    environment("SPANNER_EMULATOR_HOST", System.getenv("SPANNER_EMULATOR_HOST") ?: "localhost:9010")
    environment("PUBSUB_EMULATOR_HOST", System.getenv("PUBSUB_EMULATOR_HOST") ?: "localhost:8085")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests after the unit tests."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    dependsOn(tasks.test)
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/presentation/api/**",
                        "**/presentation/model/**",
                        "**/TodoApplicationKt.class",
                    )
                }
            },
        ),
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

val jacocoTestCoverageVerification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(jacocoTestReport)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/presentation/api/**",
                        "**/presentation/model/**",
                        "**/TodoApplicationKt.class",
                    )
                }
            },
        ),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com/example/todo/domain*")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com/example/todo/application*")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn("buildHealth")
    dependsOn(integrationTest)
    dependsOn(tasks.named("openApiValidate"))
    dependsOn("lintOpenApi")
    dependsOn(jacocoTestReport)
    dependsOn(jacocoTestCoverageVerification)
}

openApiValidate {
    inputSpec.set("$projectDir/docs/openapi.yaml")
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
    onlyIf { !file("node_modules").exists() }
}

val bundleOpenApi = tasks.register<Exec>("bundleOpenApi") {
    description = "Bundles public and internal OpenAPI specifications using OpenAPI Overlays via Redocly CLI."
    group = "documentation"
    dependsOn(npmInstall)
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "bundle:openapi")
    inputs.file("docs/openapi.yaml")
    inputs.file("docs/overlays/admin.yaml")
    inputs.file("docs/overlays/ja.yaml")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.file("docs/dist/openapi.yaml")
    outputs.file("docs/dist/openapi.admin.yaml")
    outputs.file("docs/dist/openapi.ja.yaml")
}

val bundleOpenApiJa = tasks.register("bundleOpenApiJa") {
    description = "Alias for bundleOpenApi for backwards compatibility."
    dependsOn(bundleOpenApi)
}

val lintOpenApi = tasks.register<Exec>("lintOpenApi") {
    description = "Lints OpenAPI specifications using Stoplight Spectral."
    group = "verification"
    dependsOn(npmInstall, bundleOpenApi)
    setupNodeEnvironment()
    commandLine(npmExecutable, "run", "lint:openapi")
    inputs.file("docs/openapi.yaml")
    inputs.file("docs/overlays/admin.yaml")
    inputs.file("docs/overlays/ja.yaml")
    inputs.file(".spectral.yaml")
    inputs.dir("docs/workflows")
    inputs.file("package.json")
    inputs.file("package-lock.json")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("$projectDir/docs/dist/openapi.admin.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").map { it.asFile.absolutePath })
    apiPackage.set("com.example.todo.presentation.api")
    modelPackage.set("com.example.todo.presentation.model")
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
    dependsOn(bundleOpenApi)
}

sourceSets.main {
    kotlin.srcDir(tasks.openApiGenerate.map { file("${it.outputDir.get()}/src/main/kotlin") })
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

tasks.matching { it.name == "explodeCodeSourceMain" }.configureEach {
    dependsOn(tasks.openApiGenerate)
}
