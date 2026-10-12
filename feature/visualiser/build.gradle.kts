plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.compose)
}

// The full-screen visualiser: its styles, renderers and controls (no routes: :app draws it as an overlay).
// Depends only on :core:ui (and through it the core modules), never on another feature — not even :feature:player,
// which only hands out a "open the visualiser" callback.
dependencies {
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
