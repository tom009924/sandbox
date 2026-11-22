plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
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
    api(libs.grpc.api)
    implementation(libs.opentelemetry.api)
    detektPlugins(libs.detekt.formatting)
}
