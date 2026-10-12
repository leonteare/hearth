plugins {
    id("hearth.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    // Exported schemas are committed; MigrationTest checks the hand-written migrations against them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:network"))
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
}
