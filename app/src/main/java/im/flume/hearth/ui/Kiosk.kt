// Kiosk mode: on a phone that made Hearth its device owner (a wall-mounted player for the kids), Hearth becomes
// the home screen, pins itself to the screen, keeps the lock screen out of the way and turns on kids mode.
package im.flume.hearth.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.playback.container
import kotlinx.coroutines.launch

/**
 * Set-up is a one-off over USB, on a freshly reset phone with no accounts on it:
 *
 *     adb shell dpm set-device-owner im.flume.hearth/.ui.KioskAdminReceiver
 *
 * From then on Hearth is the home screen, the lock screen never shows, and the app stays pinned: no home,
 * recents or notification shade. Volume and the power button keep working. Kids mode comes on with it
 * (`Settings.kidsMode`): no podcasts, no music that isn't on the server, a PIN in front of Settings
 * ([KioskGate]) and a "Who's listening" tab to switch between the family's logins ([ProfilesScreen]).
 *
 * To unpin for a moment from a PC run `adb shell am task lock stop`; the PIN dialog's "Phone settings" does
 * the same from the phone. To undo completely, factory reset. Phones that are not the owner see nothing of this.
 */
object Kiosk {

    /** Other apps Hearth may open while pinned: the installer for its own updates, and Settings (only via adb). */
    private val allowedPackages = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.settings",
    )

    @Volatile
    private var profileStore: KioskProfileStore? = null

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    /** Applies every kiosk policy. Safe to call on every start: it does nothing unless Hearth is the owner. */
    fun configure(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        val home = ComponentName(context, HOME_ALIAS)

        // Become the home screen: switch the alias on, then make it the one and only answer to the home intent.
        context.packageManager.setComponentEnabledSetting(
            home, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, homeFilter, home)

        // Stay pinned, with only the power menu left of the system UI; the keyguard is skipped even if a lock is set.
        dpm.setLockTaskPackages(admin, (listOf(context.packageName) + allowedPackages).toTypedArray())
        if (Build.VERSION.SDK_INT >= 28) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        }
        dpm.setKeyguardDisabled(admin, true)

        // The notification permission prompt would be the first thing on screen; the owner may grant it itself.
        if (Build.VERSION.SDK_INT >= 33) {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }

        // Kids mode stays on for as long as this phone is a kiosk.
        val session = context.container.session
        if (!session.settings.value.kidsMode) session.updateSettings { it.copy(kidsMode = true) }
    }

    /** Pins the activity to the screen when this phone is a kiosk and it is not pinned already. */
    fun pin(activity: Activity) {
        if (!isOwner(activity)) return
        val am = activity.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) activity.startLockTask()
    }

    /** The family's saved logins on this phone. */
    fun profiles(context: Context): KioskProfileStore =
        profileStore ?: synchronized(this) {
            profileStore ?: KioskProfileStore(context.applicationContext).also { profileStore = it }
        }

    /**
     * Signs [creds] in instead of whoever is listening now. Signing out first clears the old account's
     * stars and invites, so the new person's likes and playlists come down with a fresh sync.
     */
    fun switchTo(c: AppContainer, creds: Credentials) {
        c.player.stopAndClear()
        c.session.logout()
        c.session.saveCredentials(creds)
        c.appScope.launch {
            runCatching { c.sync.fullSync(runCatching { c.api.scanStatus()?.lastScan }.getOrNull()) }
        }
    }
}

/** The manifest alias that answers the home-screen intent; off until this phone is a kiosk. */
private const val HOME_ALIAS = "im.flume.hearth.ui.KioskHome" // fqn-ok
