// Declared here with apply false so the Kotlin Gradle plugin classpath is loaded
// once for the whole build (Gradle warns/breaks otherwise when two subprojects
// each apply it) — subprojects still just `alias(libs.plugins.kotlinJvm)` etc.
// No other shared config; add a convention plugin if a third module needs more.
plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
