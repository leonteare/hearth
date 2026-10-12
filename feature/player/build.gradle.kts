plugins {
    id("hearth.android.library")
    id("hearth.screenshots")
    alias(libs.plugins.kotlin.compose)
}

// The player feature: its screens and its <Feature>Routes.kt. Depends only on :core:ui (and through it the core modules),
// never on another feature.
dependencies {
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.reorderable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
