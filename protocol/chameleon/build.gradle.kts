plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM on purpose: the frame codec is the likeliest place for subtle bugs,
// so it must be exhaustively unit-testable without a device or an emulator.
dependencies {
    implementation(project(":core:domain"))

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}

kotlin {
    jvmToolchain(17)
}
