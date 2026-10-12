plugins {
    id("hearth.android.library")
}

// Asking Tidarr for music that isn't on the server, following it, and acting when it arrives.
dependencies {
    // Arrivals are matched against the library, liked, saved and downloaded through it.
    api(project(":domain:music"))
    api(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
}
