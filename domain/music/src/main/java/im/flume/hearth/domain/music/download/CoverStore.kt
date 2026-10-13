// Album art kept next to the downloads, so downloaded music still shows its cover with no server.
package im.flume.hearth.domain.music.download

import im.flume.hearth.core.network.SubsonicClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Covers for downloaded songs, saved in the app's own files rather than its cache. The image cache
 * lives in `cacheDir`, which Android empties when the phone runs short of space and the Storage page
 * can clear by hand, so a downloaded album could lose its picture and have no way to fetch it again
 * offline. One file per cover id, at the size the full player asks for, so every smaller view can be
 * scaled from it.
 */
class CoverStore(
    val dir: File,
    private val api: SubsonicClient,
    private val http: OkHttpClient,
    scope: CoroutineScope,
    /** False when art shouldn't be fetched right now, e.g. downloads are set to Wi-Fi only. */
    private val canFetch: () -> Boolean = { true },
) {
    /** Which covers are on the phone. Held in memory because screens ask for every row they draw. */
    private val saved = ConcurrentHashMap.newKeySet<String>()

    init {
        dir.mkdirs()
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.forEach { if (!it.name.endsWith(PART)) saved.add(it.name) }
        }
    }

    /** The saved cover for this id, or null if it isn't on the phone. Cheap enough to call while drawing. */
    fun fileFor(coverArtId: String?): File? {
        val name = coverArtId?.takeIf { it.isNotEmpty() }?.let(::fileName) ?: return null
        return if (saved.contains(name)) File(dir, name) else null
    }

    /** Fetches and keeps a cover, unless it's already here. Quiet on failure: art is never worth a retry storm. */
    suspend fun save(coverArtId: String?) {
        val id = coverArtId?.takeIf { it.isNotEmpty() } ?: return
        val name = fileName(id)
        if (saved.contains(name)) return
        val url = api.coverArtUrl(id, SIZE) ?: return
        withContext(Dispatchers.IO) {
            val tmp = File(dir, name + PART)
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    tmp.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) ?: error("Empty") }
                }
                val dest = File(dir, name)
                dest.delete()
                check(tmp.renameTo(dest)) { "Rename failed" }
                saved.add(name)
            }.onFailure { e ->
                tmp.delete()
                if (e is CancellationException) throw e
            }
        }
    }

    /**
     * Saves any cover a downloaded song is missing. Music downloaded before covers were kept has none,
     * and a cover can go missing if the files are cleared, so this runs at every start and does nothing
     * once they are all here.
     */
    suspend fun backfill(wanted: Collection<String>) {
        for (id in wanted) {
            if (!canFetch()) return
            save(id)
        }
    }

    /** Drops covers that no downloaded song uses any more, after downloads are removed. */
    suspend fun sweep(wanted: Collection<String>) {
        withContext(Dispatchers.IO) {
            val keep = wanted.map(::fileName).toSet()
            val names = dir.listFiles()?.map { it.name }.orEmpty()
            orphans(names, keep).forEach { name ->
                if (File(dir, name).delete()) saved.remove(name)
            }
        }
    }

    companion object {
        /** The size the full player and the notification both ask for, so one file serves every view. */
        const val SIZE = 1000
        private const val PART = ".part"

        /** A file name a cover id can't break: ids come from the server, so they can hold anything. */
        fun fileName(coverArtId: String): String {
            val safe = coverArtId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '.' }.joinToString("")
            // Keep it short and still unique: two ids that differ only in stripped characters would collide.
            return "${safe.take(60)}-${coverArtId.hashCode().toUInt().toString(16)}"
        }

        /** Of the files here, the ones no longer wanted. Half-written files are always rubbish. */
        fun orphans(names: List<String>, keep: Set<String>): List<String> =
            names.filter { it.endsWith(PART) || it !in keep }
    }
}
