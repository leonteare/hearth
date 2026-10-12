plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.compose)
}

// Test-only helpers shared by every module's tests: sample data, HearthTestTheme, a fake container for
// Robolectric, and the ScreenshotTest base class. Modules use it through testImplementation only (the
// hearth.screenshots plugin adds it), so none of this ships in the app.
dependencies {
    api(project(":core:ui"))
    api(platform(libs.compose.bom))
    api(libs.junit)
    api(libs.compose.ui.test.junit4)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
}
