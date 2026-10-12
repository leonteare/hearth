import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import java.util.Properties

/**
 * The settings every Hearth library module shares, so each module's build file only lists what's
 * different about it. The namespace comes from the module path (`:core:ui` → `im.flume.hearth.core.ui`);
 * it is also the root package of the module's code, so an import shows which module a class is in.
 * Applying the Compose compiler plugin in a module also turns Compose on and adds the Compose BOM.
 */
class HearthLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<LibraryExtension> {
            namespace = "im.flume.hearth." + path.removePrefix(":").replace(':', '.')
            compileSdk = 36
            defaultConfig {
                minSdk = 26
                if (file("consumer-rules.pro").exists()) consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
            dependencies {
                "implementation"(platform(libs.findLibrary("compose-bom").get()))
            }
        }

        dependencies {
            "testImplementation"(libs.findLibrary("junit").get())
        }
    }
}

/** The app's version name from `version.properties` (which release.sh bumps), for a module's BuildConfig. */
fun Project.hearthVersionName(): String =
    Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
        .getProperty("versionName")
