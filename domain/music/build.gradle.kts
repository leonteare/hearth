plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// The music library: sync, queries, search, playlists, mixes, lyrics, stats and song downloads.
dependencies {
    api(project(":core:data"))
    api(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
