package im.flume.hearth.ui

import im.flume.hearth.core.common.EXTRA_ROUTE
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.ui.theme.HearthColors
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch
import im.flume.hearth.feature.settings.LoginScreen

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.configure(this)
        // Before the first frame, so content is laid out edge to edge from the start.
        applySystemBars(HearthColors().isDark)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) takeRoute(intent)

        setContent {
            HearthTheme {
                val dark = HearthTheme.colors.isDark
                LaunchedEffect(dark) { applySystemBars(dark) }
                Surface(color = HearthTheme.colors.background, contentColor = HearthTheme.colors.text) {
                    val creds by container.session.credentials.collectAsStateWithLifecycle()
                    if (creds == null) {
                        LoginScreen()
                    } else {
                        AppRoot()
                    }
                }
            }
        }
    }

    /**
     * Status and navigation bar icons follow the skin: light icons on a dark skin. The bars stay see-through,
     * and "auto" keeps Android's own contrast scrim behind 3-button navigation, as before.
     */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeRoute(intent)
    }

    /** A notification asked to open a page (see [EXTRA_ROUTE]). */
    private fun takeRoute(intent: android.content.Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        intent.removeExtra(EXTRA_ROUTE)
        container.pendingRoute.value = route
    }

    override fun onStart() {
        super.onStart()
        val c = container
        c.player.connect()
        c.updater.checkIfDue()
        if (c.session.credentials.value != null) {
            c.network.checkServer()
            c.appScope.launch { runCatching { c.sync.syncIfNeeded() } }
        }
    }

    override fun onResume() {
        super.onResume()
        Kiosk.pin(this)
        container.updater.resumeAfterPermission()
    }

    override fun onStop() {
        container.player.disconnect()
        super.onStop()
    }
}

/** The scrims enableEdgeToEdge() uses by default (androidx.activity keeps them internal). */
private val LIGHT_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
