import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("hearth.screenshots")
    alias(libs.plugins.kotlin.compose)
}

val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "im.flume.hearth"
    compileSdk = 36

    defaultConfig {
        applicationId = "im.flume.hearth"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No silent fallback to the debug key: a release without keystore.properties fails (see below).
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// A release APK/bundle must carry the real signing key: without keystore.properties, fail rather than
// quietly sign with the debug key (which can't update the installed app). Debug builds are unaffected.
val hasReleaseKey = keystoreProps.containsKey("storeFile")
val requireReleaseKey = tasks.register("requireReleaseKey") {
    doLast {
        if (!hasReleaseKey) throw GradleException("Release signing needs keystore.properties (storeFile, storePassword, keyAlias, keyPassword)")
    }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "signReleaseBundle") dependsOn(requireReleaseKey)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:playback"))
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:search"))
    implementation(project(":feature:player"))
    implementation(project(":feature:spoken"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
}
