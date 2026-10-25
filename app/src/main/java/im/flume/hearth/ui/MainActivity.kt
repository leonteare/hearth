package im.flume.hearth.ui

import im.flume.hearth.core.common.EXTRA_ROUTE
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.util.Base64
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.ui.theme.HearthColors
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch
import im.flume.hearth.feature.settings.LoginScreen

/** How much one press of a volume key moves a speaker, out of 100. */
private const val VOLUME_STEP = 4

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * The volume keys belong to whatever is making the sound. While a speaker has it, this phone has no
     * sound to turn up, so they move the speaker instead.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return super.onKeyDown(keyCode, event)
        }
        val sonos = container.sonos
        val now = sonos.volumeNow()
        if (now < 0) return super.onKeyDown(keyCode, event)
        sonos.setVolume(now + step)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val ours = (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            container.sonos.volumeNow() >= 0
        return if (ours) true else super.onKeyUp(keyCode, event)
    }

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
                    // A kiosk phone remembers every login as a profile to switch back to (ProfilesScreen).
                    val kiosk = remember { Kiosk.isOwner(this@MainActivity) }
                    if (kiosk) LaunchedEffect(creds) { creds?.let { Kiosk.profiles(this@MainActivity).record(it) } }
                    if (creds == null) {
                        if (kiosk) KioskLogin() else LoginScreen()
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
        // Kiosk set-up over adb (ignored unless this phone is a kiosk):
        //   am start -n im.flume.hearth/.ui.MainActivity --es kiosk_pin 2580          sets or replaces the PIN
        //   am start -n im.flume.hearth/.ui.MainActivity --es kiosk_ca "$(base64 -w0 root.crt)"   trusts a home CA
        intent?.getStringExtra("kiosk_pin")?.let {
            intent.removeExtra("kiosk_pin")
            if (Kiosk.isOwner(this)) KioskAccess.setPin(this, it)
        }
        intent?.getStringExtra("kiosk_ca")?.let {
            intent.removeExtra("kiosk_ca")
            runCatching { Kiosk.installCa(this, String(Base64.decode(it, Base64.DEFAULT))) }
        }
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
