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
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform {
        excludeTags("persistence")
    }
}

val persistenceIntegrationTest = tasks.register<Test>("persistenceIntegrationTest") {
    description = "Runs Spanner persistence slice and dynamic smoke tests (requires Spanner Emulator)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("todo.grpc.enabled", "false")
    useJUnitPlatform {
        includeTags("persistence")
    }
}

val generateExternalCallDiagrams = tasks.register<Test>("generateExternalCallDiagrams") {
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
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    mustRunAfter(persistenceIntegrationTest, generateExternalCallDiagrams)
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
                        "**/TodoApplicationKt.class",
                        "**/infrastructure/persistence/**",
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
    executionData.setFrom(jacocoTestReport.map { it.executionData })
    classDirectories.setFrom(jacocoTestReport.map { it.classDirectories })
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com/example/todo/domain*")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
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

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/dmn/src/main/kotlin"))
}

tasks.compileKotlin {
    dependsOn(rootProject.tasks.named("generateDmnModel"))
}

tasks.matching { it.name == "explodeCodeSourceMain" }.configureEach {
    dependsOn(rootProject.tasks.named("generateDmnModel"))
}

tasks.check {
    dependsOn(tasks.named("openApiValidate"))
    dependsOn(jacocoTestReport)
    dependsOn(jacocoTestCoverageVerification)
}
