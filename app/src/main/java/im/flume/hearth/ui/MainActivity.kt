package im.flume.hearth.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import im.flume.hearth.container
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            HearthTheme {
                Surface(color = Background, contentColor = Color.White) {
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

    override fun onStart() {
        super.onStart()
        val c = container
        c.player.connect()
        c.updater.checkIfDue()
        if (c.session.credentials.value != null) {
            c.network.checkServer()
            lifecycleScope.launch { runCatching { c.sync.syncIfNeeded() } }
        }
    }

    override fun onResume() {
        super.onResume()
        container.updater.resumeAfterPermission()
    }

    override fun onStop() {
        container.player.disconnect()
        super.onStop()
    }
}
