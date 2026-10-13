plugins {
    kotlin("jvm") version "1.9.23"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.api.grpc:grpc-google-cloud-spanner-v1:6.118.0")
    implementation("io.grpc:grpc-netty-shaded:1.81.0")
    testImplementation("io.grpc:grpc-inprocess:1.81.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.spannerproxy.SpannerProxyKt")
}

tasks.test {
    useJUnitPlatform()
}
