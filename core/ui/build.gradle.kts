plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    api(project(":core:playback"))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.palette)
}
