plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    api(libs.media3.exoplayer)
    api(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.coil)

    testImplementation(libs.kotlinx.coroutines.test)
}
