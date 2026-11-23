plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

configurations.matching { it.name.contains("test") || it.name.contains("Test") }.configureEach {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "org.apache.groovy" -> useVersion("4.0.26")
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
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
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
