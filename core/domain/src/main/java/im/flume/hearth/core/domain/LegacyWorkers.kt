// Runs background jobs that phones saved under a worker's old class name (before each module got its own package).
package im.flume.hearth.core.domain

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * WorkManager saves every scheduled job with its worker's full class name and looks the class up by that name
 * when the job runs. Jobs queued by 2.20.1 or earlier (downloads, the Tidarr poll, podcast sync, progress
 * uploads) still carry the names from before the package move, so without this they'd fail to start and be
 * dropped. Periodic jobs are kept rather than replaced, so these names can turn up indefinitely: never remove one.
 */
object LegacyWorkers {
    val NAMES: Map<String, String> = mapOf(
        "im.flume.hearth.download.DownloadWorker" to "im.flume.hearth.domain.music.download.DownloadWorker",
        "im.flume.hearth.requests.RequestsWorker" to "im.flume.hearth.domain.requests.RequestsWorker",
        "im.flume.hearth.spoken.SpokenSyncWorker" to "im.flume.hearth.domain.spoken.SpokenSyncWorker",
        "im.flume.hearth.spoken.SpokenDownloadWorker" to "im.flume.hearth.domain.spoken.SpokenDownloadWorker",
        "im.flume.hearth.spoken.OutboxWorker" to "im.flume.hearth.domain.spoken.OutboxWorker",
        "im.flume.hearth.spoken.LocalBookUploadWorker" to "im.flume.hearth.domain.spoken.LocalBookUploadWorker",
    )

    /** The worker class a saved job's [className] now refers to, or null if it isn't an old name. */
    fun currentName(className: String): String? = NAMES[className]
}

/**
 * Builds workers saved under an old class name; returns null for anything else so WorkManager's own
 * factory handles current names as usual.
 */
class LegacyWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        val name = LegacyWorkers.currentName(workerClassName) ?: return null
        return Class.forName(name).asSubclass(ListenableWorker::class.java)
            .getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            .newInstance(appContext, workerParameters)
    }
}
