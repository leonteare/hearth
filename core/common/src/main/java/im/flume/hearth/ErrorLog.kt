package im.flume.hearth

import android.content.Context
import im.flume.hearth.core.common.BuildConfig
import im.flume.hearth.util.scrubSecrets
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.util.Date

fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")

/** Errors that were caught and handled; kept for debugging, never shown as a crash. */
fun errorLogFile(context: Context) = File(context.filesDir, "errors.log")

/** Report text with URLs and login parameters scrubbed out (stream URLs carry the token). */
private fun report(where: String, e: Throwable): String = scrubSecrets(
    "Hearth ${BuildConfig.VERSION_NAME} on Android ${android.os.Build.VERSION.RELEASE}, " +
        "${Date()}, $where\n\n${e.stackTraceToString()}"
)

/** Saves a real (uncaught) crash so it can be copied from Home next time the app opens. Never throws. */
fun writeCrash(context: Context, where: String, e: Throwable) {
    runCatching { crashFile(context).writeText(report(where, e)) }
}

private val errorLogLock = Any()
private const val ERROR_LOG_MAX = 64 * 1024
private const val ERROR_LOG_SEPARATOR = "\n\n----\n\n"

/**
 * Appends a handled error to [errorLogFile], keeping only the newest ~64 KB. Never throws.
 * Appending is the common case; the file is only read and cut down once it grows past the cap,
 * so a burst of errors (e.g. a flaky connection) doesn't rewrite 64 KB each time.
 */
fun logHandledError(context: Context, where: String, e: Throwable) {
    runCatching {
        synchronized(errorLogLock) {
            val file = errorLogFile(context)
            file.appendText(report(where, e) + ERROR_LOG_SEPARATOR)
            if (file.length() > ERROR_LOG_MAX) file.writeText(newestLogEntries(file.readText(), ERROR_LOG_MAX * 3 / 4))
        }
    }
}

/**
 * The end of an error log, at most [maxChars] long, starting at a whole entry where possible. Trimmed
 * well under the cap so the next trim is many errors away.
 */
internal fun newestLogEntries(log: String, maxChars: Int): String {
    if (log.length <= maxChars) return log
    val tail = log.takeLast(maxChars)
    val cut = tail.indexOf(ERROR_LOG_SEPARATOR)
    return if (cut >= 0 && cut + ERROR_LOG_SEPARATOR.length < tail.length) tail.substring(cut + ERROR_LOG_SEPARATOR.length) else tail
}

/**
 * For long-lived scopes: a failed background job (e.g. a database error) is logged instead of
 * crashing the app, and isn't reported as a crash. Cancellation never reaches a handler.
 */
fun crashLoggingHandler(context: Context, name: String) = CoroutineExceptionHandler { _, e ->
    logHandledError(context, "caught in $name", e)
}
