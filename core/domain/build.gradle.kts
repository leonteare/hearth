plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:data"))
    api(project(":domain:music"))
    api(project(":domain:spoken"))
    api(project(":domain:update"))
    api(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
