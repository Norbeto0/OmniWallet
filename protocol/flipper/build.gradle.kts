plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
}

// Pure JVM on purpose. The framing, the flow-control credit accounting and the
// has_next assembly are the subtle parts of this project, and this is the only
// layer that can be exhaustively tested without hardware.
wire {
    kotlin {}
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.wire.runtime)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

kotlin {
    jvmToolchain(17)
}
