plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        // Self-update source (update/Updater.kt); release.sh publishes to this repo.
        buildConfigField("String", "UPDATE_REPO", "\"leonteare/hearth\"")
        buildConfigField("String", "VERSION_NAME", "\"${hearthVersionName()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:data"))
    api(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
