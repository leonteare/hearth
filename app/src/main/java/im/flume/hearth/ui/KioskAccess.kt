// The grown-ups' way past kids mode: the PIN that guards Settings, and the door out of the kiosk to the
// phone's own settings.
package im.flume.hearth.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit

object KioskAccess {

    private fun prefs(context: Context) = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** The PIN set on this phone, or null until a grown-up chooses one (the gate asks the first time). */
    fun pin(context: Context): String? = prefs(context).getString("pin", null)

    /** Sets or replaces the PIN; anything that isn't 4 to 8 digits is ignored. */
    fun setPin(context: Context, pin: String) {
        if (KioskPin.valid(pin)) prefs(context).edit { putString("pin", pin) }
    }

    fun matches(context: Context, entered: String): Boolean = pin(context)?.let { it == entered } ?: false

    /**
     * Unpins Hearth and opens Android's Settings (Wi-Fi, charging limit, updates). Coming back to Hearth
     * pins it again (MainActivity.onResume), so the door closes behind you.
     */
    fun leave(activity: Activity) {
        runCatching { activity.stopLockTask() }
        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** What counts as a kiosk PIN: 4 to 8 digits. */
object KioskPin {
    fun valid(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }
}
