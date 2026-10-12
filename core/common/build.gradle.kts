plugins {
    id("hearth.android.library")
}

android {
    defaultConfig {
        // Error reports name the app version (see ErrorLog.kt).
        buildConfigField("String", "VERSION_NAME", "\"${hearthVersionName()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
