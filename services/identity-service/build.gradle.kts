plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.cyclonedx)
    jacoco
}

dependencies {
    implementation(project(":libs:grpc-common"))
    implementation(project(":libs:identity-proto"))
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
    runtimeOnly(libs.jackson.module.kotlin)
    runtimeOnly(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.opentelemetry.api)
    detektPlugins(libs.detekt.formatting)
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/IdentityApplicationKt.class",
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
    classDirectories.setFrom(jacocoTestReport.map { it.classDirectories })
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(jacocoTestReport)
    dependsOn(jacocoTestCoverageVerification)
}
