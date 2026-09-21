plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

// This module is the whole point of the strangler-fig plan: the Repgen parser
// and the Symitar session/protocol logic live here with ZERO UI dependency
// (no Compose, no Swing, no SWT). Any UI (this desktop app, a future web/mobile
// one) is just a client of this module.

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sshd.core)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
