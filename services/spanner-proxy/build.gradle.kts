plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
}

dependencies {
    implementation(libs.grpc.api)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.spring.cloud.gcp.starter.data.spanner)
    detektPlugins(libs.detekt.formatting)
}
