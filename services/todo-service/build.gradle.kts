plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.cyclonedx)
    jacoco
}

detekt {
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/integrationTest/kotlin",
        ),
    )
}

configurations.matching { it.name.startsWith("integrationTest") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.groovy") {
            useVersion("4.0.26")
        }
    }
}

configurations.matching { it.name.contains("test") || it.name.contains("Test") }.configureEach {
    exclude(group = "org.eclipse.jetty", module = "jetty-servlet")
    exclude(group = "org.eclipse.jetty", module = "jetty-servlets")
    exclude(group = "org.eclipse.jetty", module = "jetty-webapp")
    exclude(group = "org.eclipse.jetty.http2", module = "http2-server")
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.eclipse.jetty")) {
            useVersion("12.1.10")
        }
    }
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
    implementation(project(":libs:grpc-common"))
    implementation(project(":libs:identity-proto"))
    implementation(project(":libs:todo-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.cloud.gcp.starter.data.spanner)
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
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.wiremock.core)
    testRuntimeOnly(libs.wiremock)
    testRuntimeOnly(libs.jackson.module.kotlin)
    add(integrationTestSourceSet.implementationConfigurationName, libs.rest.assured)
    add(integrationTestSourceSet.implementationConfigurationName, libs.swagger.parser)
    add(integrationTestSourceSet.implementationConfigurationName, libs.wiremock.core)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotest.property)
    add(integrationTestSourceSet.runtimeOnlyConfigurationName, libs.wiremock)
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
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
    useJUnitPlatform {
        excludeTags("property")
    }
    testLogging {
        showStandardStreams = true
    }
}

val propertyTest = tasks.register<Test>("propertyTest") {
    description = "Runs property-based tests after the integration tests."
    group = "verification"
    maxHeapSize = "512m"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    dependsOn(integrationTest)
    shouldRunAfter(integrationTest)
    useJUnitPlatform {
        includeTags("property")
    }
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Test>("generateExternalCallDiagrams") {
    description = "Generates Mermaid external call sequence diagrams from dynamic traces."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.example.todo.trace.ExternalCallSequenceDiagramGeneratorTest")
    }
    systemProperty(
        "externalCallDiagramOut",
        "${rootProject.projectDir}/docs/architecture/external-call-sequence.md",
    )
    outputs.upToDateWhen { false }
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test, integrationTest, propertyTest)
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
    dependsOn(jacocoTestReport, propertyTest)
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

openApiValidate {
    inputSpec.set("${rootProject.projectDir}/docs/dist/openapi.yaml")
}

tasks.named("openApiValidate") {
    dependsOn(rootProject.tasks.named("bundleOpenApi"))
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("${rootProject.projectDir}/docs/dist/openapi.admin.yaml")
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
    dependsOn(rootProject.tasks.named("bundleOpenApi"))
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

tasks.check {
    dependsOn(integrationTest)
    dependsOn(propertyTest)
    dependsOn(tasks.named("openApiValidate"))
    dependsOn(jacocoTestReport)
    dependsOn(jacocoTestCoverageVerification)
}
