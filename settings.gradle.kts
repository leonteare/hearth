pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Hearth"
include(":app")
include(":core:common")
include(":core:network")
include(":core:data")
include(":core:domain")
include(":domain:music")
include(":domain:spoken")
include(":domain:requests")
include(":domain:update")
include(":core:playback")
include(":core:ui")
include(":core:testing")
include(":feature:home")
include(":feature:library")
include(":feature:search")
include(":feature:player")
include(":feature:spoken")
include(":feature:settings")
