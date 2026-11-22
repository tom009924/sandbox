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
        ),
    )
}

dependencies {
    implementation(project(":libs:grpc-common"))
    implementation(project(":libs:identity-proto"))
    implementation(project(":libs:todo-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
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
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.opentelemetry.api)
    detektPlugins(libs.detekt.formatting)
}

tasks.named<Jar>("jar") {
    enabled = false
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

tasks.matching { it.name == "explodeCodeSourceMain" }.configureEach {
    dependsOn(tasks.openApiGenerate)
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/presentation/api/**",
                        "**/presentation/model/**",
                        "**/ApiGatewayApplicationKt.class",
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
    classDirectories.setFrom(jacocoTestReport.map { it.classDirectories })
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(jacocoTestReport)
    dependsOn(jacocoTestCoverageVerification)
}
