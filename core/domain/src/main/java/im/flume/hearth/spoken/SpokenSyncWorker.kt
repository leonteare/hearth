// The background job that syncs podcasts and audiobooks every few hours.
package im.flume.hearth.spoken

import im.flume.hearth.domainContainer
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Every few hours: send unsent progress, refresh the catalogue, auto-download new episodes. */
class SpokenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val spoken = applicationContext.domainContainer.spoken
        if (spoken.account.value == null) return Result.success()
        spoken.refresh(force = true)
        return Result.success()
    }
}
