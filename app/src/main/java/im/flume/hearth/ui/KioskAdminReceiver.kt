// The device-admin hook Android needs before Hearth can be a phone's owner; it applies the kiosk policies.
package im.flume.hearth.ui

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class KioskAdminReceiver : DeviceAdminReceiver() {

    /** Fires once, when `dpm set-device-owner` completes. */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Kiosk.configure(context)
    }
}
