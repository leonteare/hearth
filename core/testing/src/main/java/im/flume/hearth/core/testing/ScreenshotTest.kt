// The base class for screenshot tests: a fixed phone, today's dark theme, one PNG per test.
package im.flume.hearth.core.testing

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import im.flume.hearth.core.ui.components.Actions
import im.flume.hearth.core.ui.components.LiveRows
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Extend this and call [snap] (one composable, cropped to its own size) or [snapScreen] (the whole phone
 * screen, including sheets and dialogs, which open in windows of their own). Images go to
 * `src/test/screenshots/<TestClass>_<name>.png` in the module. Plain test runs compare against them;
 * `-Proborazzi.test.record=true` rewrites them (see the hearth.screenshots plugin).
 *
 * SDK 34, not 35: on 35+ Robolectric's native graphics can't find its own jar when the home folder has a space
 * in it (as on the owner's PC), so every screenshot test fails to start.
 *
 * Kept still on purpose: a fixed SDK, phone size and density, the default accent and Robolectric's own fonts,
 * and the Compose clock runs only when the test asks, so infinite animations stop at their first frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = SCREENSHOT_DEVICE, application = TestApplication::class)
abstract class ScreenshotTest {
    private val compose: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    /** Registers the plain activity Compose tests launch, which library modules' manifests don't declare. */
    private val registerActivity = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<TestApplication>()
                // No action bar, like the app's own theme, so full-screen shots show only the page.
                val info = ActivityInfo().apply {
                    name = ComponentActivity::class.java.name
                    packageName = app.packageName
                    theme = android.R.style.Theme_Material_NoActionBar
                }
                shadowOf(app.packageManager).addOrUpdateActivity(info)
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(compose)

    /** The fake container behind `LocalContext.current.container`, to seed its in-memory database. */
    protected val container: TestContainer
        get() = ApplicationProvider.getApplicationContext<TestApplication>().container

    /** Shoots [content] at its own size (full phone width when it fills the width). */
    protected fun snap(
        name: String,
        actions: Actions = FakeActions(),
        live: LiveRows = liveRows(),
        content: @Composable () -> Unit,
    ) {
        show(actions, live) { Box(Modifier.testTag(SHOT)) { content() } }
        compose.onNodeWithTag(SHOT).captureRoboImage(path(name), roborazziOptions = OPTIONS)
    }

    /** Shoots the whole phone screen with [content] filling it: pages, and anything that opens a sheet or dialog. */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun snapScreen(
        name: String,
        actions: Actions = FakeActions(),
        live: LiveRows = liveRows(),
        content: @Composable () -> Unit,
    ) {
        show(actions, live) { Box(Modifier.fillMaxSize()) { content() } }
        captureScreenRoboImage(path(name), roborazziOptions = OPTIONS)
    }

    /**
     * Sets the content, then runs the Compose clock a fixed [SETTLE_MS] by hand: sheets finish sliding in and
     * crossfades finish, while a spinner (which never settles) is caught at the same moment every run.
     */
    private fun show(actions: Actions, live: LiveRows, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { HearthTestTheme(actions, live, content) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun path(name: String) = "src/test/screenshots/${javaClass.simpleName}_$name.png"

    private companion object {
        const val SHOT = "screenshot"
        const val SETTLE_MS = 2_000L
        val OPTIONS = RoborazziOptions()
    }
}

/** A typical phone (411 x 891 dp, xxhdpi), the size every screenshot is taken at. */
const val SCREENSHOT_DEVICE = "w411dp-h891dp-xxhdpi"
