plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

configurations.matching { it.name.contains("test") || it.name.contains("Test") }.configureEach {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "org.apache.groovy" -> useVersion("4.0.26")
            requested.group == "io.grpc" && requested.name != "grpc-grpclb" -> useVersion(libs.versions.grpc.get())
            requested.group == "io.opentelemetry" -> useVersion(libs.versions.opentelemetry.get())
            requested.group == "com.google.protobuf" && requested.name == "protobuf-java" ->
                useVersion(libs.versions.protobuf.get())
            requested.group == "com.google.guava" && requested.name == "guava" -> useVersion("33.7.2-jre")
            requested.group == "commons-io" && requested.name == "commons-io" -> useVersion("2.19.0")
            requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> useVersion("4.5.14")
        }
    }
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.rest.assured)
    testImplementation(libs.swagger.parser)
    testImplementation(libs.kotest.property)
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testRuntimeOnly(libs.spanner.jdbc)
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
    environment("SPANNER_EMULATOR_HOST", System.getenv("SPANNER_EMULATOR_HOST") ?: "127.0.0.1:9010")
    environment("PUBSUB_EMULATOR_HOST", System.getenv("PUBSUB_EMULATOR_HOST") ?: "127.0.0.1:8085")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("property", "apigee")
    }
}

tasks.register<Test>("propertyTest") {
    description = "Runs container-backed property and model-based tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("property")
    }
}
