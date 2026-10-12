import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * Screenshot tests for a module: add `id("hearth.screenshots")` to its `plugins { }` block, then write tests that
 * extend `ScreenshotTest` (from `:core:testing`). They run on the JVM with Robolectric and Roborazzi; no device.
 *
 * Reference images live in the module's `src/test/screenshots/` and are committed. A plain `testDebugUnitTest`
 * (and so `./verify.sh`) compares every screenshot with its reference and fails the test that changed; the
 * differences land in `build/outputs/roborazzi/`. To accept a new look, record instead:
 * `./gradlew testDebugUnitTest -Proborazzi.test.record=true`.
 *
 * Roborazzi's own Gradle plugin isn't used: it only adds record/verify tasks, and this keeps verify the default.
 */
class HearthScreenshotsPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).get()

        pluginManager.withPlugin("com.android.base") {
            extensions.getByType(CommonExtension::class.java).testOptions.unitTests.isIncludeAndroidResources = true
        }

        dependencies {
            if (path != ":core:testing") "testImplementation"(project(":core:testing"))
            "testImplementation"(platform(lib("compose-bom")))
            "testImplementation"(lib("roborazzi"))
            "testImplementation"(lib("roborazzi-compose"))
            "testImplementation"(lib("robolectric"))
        }

        // Record when asked (-Proborazzi.test.record=true), or compare without failing (-Proborazzi.test.compare=true);
        // otherwise verify. The mode and the references are inputs, so a changed image or mode re-runs the tests.
        val record = providers.gradleProperty("roborazzi.test.record").orNull == "true"
        val compare = providers.gradleProperty("roborazzi.test.compare").orNull == "true"
        val mode = when {
            record -> "record"
            compare -> "compare"
            else -> "verify"
        }
        val references = layout.projectDirectory.dir("src/test/screenshots")
        val diffs = layout.buildDirectory.dir("outputs/roborazzi")
        tasks.withType<Test>().configureEach {
            systemProperty("roborazzi.test.record", (mode == "record").toString())
            systemProperty("roborazzi.test.compare", (mode == "compare").toString())
            systemProperty("roborazzi.test.verify", (mode == "verify").toString())
            systemProperty("roborazzi.compare.output.dir", diffs.get().asFile.absolutePath)
            systemProperty("robolectric.graphicsMode", "NATIVE")
            inputs.property("roborazzi.mode", mode)
            inputs.files(fileTree(references)).withPropertyName("screenshotReferences")
            if (mode == "record") outputs.upToDateWhen { false }
        }
    }
}
