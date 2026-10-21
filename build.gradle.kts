plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group = "com.example"
version = "1.0.0"

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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.google.cloud:google-cloud-spanner-hibernate-dialect:3.9.1")
    runtimeOnly("com.google.cloud:google-cloud-spanner-jdbc:2.29.1")
    implementation("com.google.cloud:google-cloud-pubsub:1.137.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    add(integrationTestSourceSet.implementationConfigurationName, "io.rest-assured:rest-assured:5.5.7")
    add(integrationTestSourceSet.implementationConfigurationName, "io.swagger.parser.v3:swagger-parser:2.1.22")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    maxParallelForks = 1
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
    dependsOn(integrationTest)
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
