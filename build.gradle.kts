plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = "1.0.0"

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
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.jackson.annotations)
    runtimeOnly(libs.spanner.hibernate.dialect)
    runtimeOnly(libs.spanner.jdbc)
    implementation(libs.google.cloud.pubsub)
    runtimeOnly(libs.jackson.module.kotlin)
    runtimeOnly(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jackson.module.kotlin)
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
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests after the unit tests."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    dependsOn(tasks.test)
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}

tasks.check {
    dependsOn("buildHealth")
    dependsOn(integrationTest)
    dependsOn(tasks.named("openApiValidate"))
    dependsOn("lintOpenApi")
}

openApiValidate {
    inputSpec.set("$projectDir/docs/openapi.yaml")
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmExecutable = if (isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    description = "Installs Node dependencies using npm ci."
    group = "build setup"
    commandLine(npmExecutable, "ci")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
    onlyIf { !file("node_modules").exists() }
}

val lintOpenApi = tasks.register<Exec>("lintOpenApi") {
    description = "Lints OpenAPI specifications using Stoplight Spectral."
    group = "verification"
    dependsOn(npmInstall)
    commandLine(npmExecutable, "run", "lint:openapi")
    inputs.file("docs/openapi.yaml")
    inputs.file(".spectral.yaml")
    inputs.file("package.json")
    inputs.file("package-lock.json")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("$projectDir/docs/openapi.yaml")
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

sourceSets.main {
    kotlin.srcDir(tasks.openApiGenerate.map { file("${it.outputDir.get()}/src/main/kotlin") })
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

tasks.matching { it.name == "explodeCodeSourceMain" }.configureEach {
    dependsOn(tasks.openApiGenerate)
}
