// The background job that syncs podcasts and audiobooks every few hours.
package im.flume.hearth.spoken

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import im.flume.hearth.HearthApp

/** Every few hours: send unsent progress, refresh the catalogue, auto-download new episodes. */
class SpokenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val spoken = (applicationContext as HearthApp).container.spoken
        if (spoken.account.value == null) return Result.success()
        spoken.refresh(force = true)
        return Result.success()
    }
}
