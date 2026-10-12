plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.compose)
}

// The settings feature: its screens and its <Feature>Routes.kt. Depends only on :core:ui (and through it the core modules),
// never on another feature.
dependencies {
    implementation(project(":core:ui"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
