plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the root build already puts AGP on the classpath, so the plugin uses that same copy.
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("hearthLibrary") {
            id = "hearth.android.library"
            implementationClass = "HearthLibraryPlugin"
        }
    }
}
