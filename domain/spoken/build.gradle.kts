plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Podcasts and audiobooks (Audiobookshelf), and audiobooks kept only on the phone.
dependencies {
    api(project(":core:data"))
    api(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    // MetadataRetriever reads chapters and lengths from phone audiobooks (LocalBookScanner).
    implementation(libs.media3.exoplayer)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
