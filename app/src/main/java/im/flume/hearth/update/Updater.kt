package im.flume.hearth.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import im.flume.hearth.BuildConfig
import im.flume.hearth.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Self-updates from the latest GitHub release of [BuildConfig.UPDATE_REPO]. The APK is installed with
 * PackageInstaller; Android asks for one confirmation tap (and may skip it on later updates, once
 * Hearth is the app that installed itself).
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Asks GitHub at most every [CHECK_INTERVAL] unless [force]d. In between, a fresh start shows the
     * result of the last check, so an available update still appears without a request.
     */
    fun checkIfDue(force: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        val elapsed = System.currentTimeMillis() - prefs.getLong("lastCheck", 0)
        if (!force && elapsed in 0 until CHECK_INTERVAL) {
            if (s is UpdateState.Idle) savedRelease()?.let { _state.value = UpdateState.Available(it) }
            return
        }
        scope.launch { check() }
    }

    private fun savedRelease(): Release? {
        val version = prefs.getString("version", null) ?: return null
        if (!isNewer(version, currentVersion)) return null
        return Release(version, prefs.getString("notes", "").orEmpty(), prefs.getString("apkUrl", null) ?: return null, prefs.getLong("size", 0))
    }

    private suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = fetchLatest()
            prefs.edit {
                putLong("lastCheck", System.currentTimeMillis())
                if (release == null) {
                    remove("version")
                } else {
                    putString("version", release.version)
                    putString("notes", release.notes)
                    putString("apkUrl", release.apkUrl)
                    putLong("size", release.sizeBytes)
                }
            }
            if (release != null && isNewer(release.version, currentVersion)) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: Exception) {
            UpdateState.Failed("Couldn't check for updates (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
            val apk = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return@withContext null
            Release(
                version = tag.removePrefix("v"),
                notes = root["body"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        scope.launch {
            try {
                val file = download(release)
                val installing = UpdateState.Installing(release)
                _state.value = installing
                withContext(Dispatchers.IO) { installApk(file) }
                // If Android silently blocks the confirmation, no result ever arrives; let the user try again.
                delay(INSTALL_TIMEOUT)
                if (_state.value === installing) _state.value = UpdateState.Available(release)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Update failed (${e.message ?: e.javaClass.simpleName})", release)
            }
        }
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Hearth-${release.version}.apk")
        _state.value = UpdateState.Downloading(release, 0f)
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) done.toFloat() / total else 0f
                        if (p - lastReported >= 0.02f) {
                            lastReported = p
                            _state.value = UpdateState.Downloading(release, p)
                        }
                    }
                }
            }
        }
        file
    }

    private fun installApk(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("hearth.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val s = _state.value
        // Available too: a slow confirmation may have hit the install timeout before this result came in.
        val release = (s as? UpdateState.Installing)?.release ?: (s as? UpdateState.Available)?.release
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && release != null) {
                UpdateState.Available(release)
            } else {
                UpdateState.Failed("Update failed: ${message ?: "status $status"}", release)
            }
        }
    }

    /** Called when the app returns from the "install unknown apps" settings screen. */
    fun resumeAfterPermission() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) install(s.release)
    }

    companion object {
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        private const val INSTALL_TIMEOUT = 2 * 60 * 1000L

        /** True if dotted version [candidate] is greater than [current]. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
        context.container.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
