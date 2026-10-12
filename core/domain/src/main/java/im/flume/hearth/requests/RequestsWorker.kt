package im.flume.hearth.requests

import im.flume.hearth.requestsContainer
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Follows requests every 15 minutes while the app is closed; cancels itself once nothing is in progress. */
class RequestsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.requestsContainer
        val more = runCatching { c.requests.poll() }.getOrDefault(true)
        if (!more) WorkManager.getInstance(applicationContext).cancelUniqueWork(RequestsRepository.WORK_NAME)
        return Result.success()
    }
}
