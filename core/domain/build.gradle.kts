plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain-JVM module with NO Android dependency. That is
// load-bearing: it makes it impossible to leak a Flipper- or Chameleon-shaped
// detail into the abstraction, and it keeps the domain unit-testable without
// an emulator.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}
