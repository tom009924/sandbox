plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}
