package im.flume.hearth.core.common

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private val urlPattern = Regex("""https?://\S+""")

// Subsonic login parameters: t (token), s (salt), p (password, plain or enc:hex), plus spelled-out forms.
private val secretParam = Regex("""(?i)(^|[?&;,\s])(t|s|p|token|salt|password)=[^&\s]*""")

/**
 * Removes anything that could carry the login from [text]: every URL becomes "the server" (stream
 * and cover URLs include the Subsonic token and salt), and stray `t=`/`s=`/`p=` parameters are blanked.
 */
fun scrubSecrets(text: String): String =
    text.replace(urlPattern, "the server")
        .replace(secretParam) { m -> "${m.groupValues[1]}${m.groupValues[2]}=***" }

/** Shown when the phone can't get to the server at all, including when it's offline. */
const val NO_CONNECTION_MESSAGE = "Can't reach the server. Check you're online."
const val TIMEOUT_MESSAGE = "The server took too long to answer."
const val SIGN_IN_REFUSED_MESSAGE = "Your sign-in was refused. Check your username and password."
const val SERVER_PROBLEM_MESSAGE = "The server had a problem. Try again in a bit."
const val GENERIC_ERROR_MESSAGE = "Something went wrong"

/** Long low-level messages are cut so a snackbar stays readable. */
private const val DETAIL_MAX = 80

private val httpCode = Regex("""HTTP (\d{3})""")

/**
 * A short, plain sentence for [e] that's safe to show. Common failures (no connection, timeouts,
 * a refused sign-in, a broken server) get a fixed sentence, because the raw text ("Unable to resolve
 * host…", "SocketTimeoutException") means nothing to a listener. Hearth's own errors are already
 * written for people, so their message is kept. Anything else becomes [fallback] ("Something went
 * wrong") plus a short, scrubbed detail, which helps when the problem is reported back.
 */
fun userMessage(e: Throwable, fallback: String = GENERIC_ERROR_MESSAGE): String {
    val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
    val code = chain.firstNotNullOfOrNull(::statusCode)
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        chain.any { it is SocketTimeoutException } || "timed out" in text || "timeout" in text -> TIMEOUT_MESSAGE
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            NO_CONNECTION_MESSAGE
        code == 401 || code == 403 || chain.any { it is WrongPasswordException } -> SIGN_IN_REFUSED_MESSAGE
        code != null && code in 500..599 -> SERVER_PROBLEM_MESSAGE
        else -> {
            val message = e.message?.takeIf { it.isNotBlank() }?.let(::scrubSecrets)
            when {
                message == null -> fallback
                // Hearth's own exceptions already say what happened in plain words.
                e.javaClass.name.startsWith("im.flume.hearth.") && code == null -> message
                else -> "$fallback (${message.take(DETAIL_MAX).trimEnd()}${if (message.length > DETAIL_MAX) "…" else ""})"
            }
        }
    }
}

/**
 * "[what]. [reason]" for a failed action, e.g. "Couldn't update Liked Songs. Can't reach the server.
 * Check you're online." When the phone is known to be [offline] the reason is the connection, whatever
 * [e] says, because a timeout or reset while offline is really just "no connection".
 */
fun couldNot(what: String, e: Throwable?, offline: Boolean = false): String = when {
    offline -> "$what. $NO_CONNECTION_MESSAGE"
    e != null -> "$what. ${userMessage(e)}"
    else -> "$what."
}

/** The HTTP status [e] carries, if any (Subsonic and podcast-server errors, or an "HTTP 502" message). */
private fun statusCode(e: Throwable): Int? = when (e) {
    is SubsonicException -> when (e.code) {
        40, 41 -> 401 // Subsonic: wrong username or password, or token sign-in not allowed
        in 100..599 -> e.code
        else -> null
    }
    is AbsHttpException -> e.code
    else -> e.message?.let { httpCode.find(it)?.groupValues?.get(1)?.toInt() }
}
