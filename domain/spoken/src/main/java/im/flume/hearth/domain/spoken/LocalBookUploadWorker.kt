// The background job that adds a phone audiobook to the server, so it finishes with the app closed.
package im.flume.hearth.domain.spoken

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import im.flume.hearth.core.common.R

/** Adds one phone book to the server, showing a notification so it can finish with the app closed. */
class LocalBookUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ARG_ID) ?: return Result.success()
        val local = applicationContext.spokenContainer.spoken.local
        runCatching { setForeground(foregroundInfo()) }
        local.runUpload(id)
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Adding an audiobook to the server")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    companion object {
        const val ARG_ID = "id"
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 44
    }
}
