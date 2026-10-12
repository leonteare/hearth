plugins {
    id("hearth.android.library")
}

// Self-update from GitHub Releases (update/Updater.kt).
android {
    defaultConfig {
        // release.sh publishes to this repo.
        buildConfigField("String", "UPDATE_REPO", "\"leonteare/hearth\"")
        buildConfigField("String", "VERSION_NAME", "\"${hearthVersionName()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:data"))
    implementation(libs.androidx.core.ktx)
}
