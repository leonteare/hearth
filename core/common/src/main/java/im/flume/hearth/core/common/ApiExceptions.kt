// The errors the server clients throw. They live here, below the clients, so userMessage can
// recognise them and give a plain sentence (see util/Errors.kt).
package im.flume.hearth.core.common

import java.io.IOException

class SubsonicException(val code: Int, message: String) : IOException(message) {
    /** Subsonic error 50: the user isn't allowed to do this (e.g. edit someone else's playlist). */
    val notAuthorized: Boolean get() = code == 50
}

/** Navidrome turned the password down (wrong, or changed since it was saved). */
class WrongPasswordException : IOException("Navidrome didn't accept the password")

/** No password saved yet; the UI should ask for it once. */
class PasswordNeededException : IOException("Password needed")

open class AbsException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server answered with an error status. [body] is a short, token-free excerpt. */
class AbsHttpException(val code: Int, val body: String) : AbsException("The podcast server answered HTTP $code${if (body.isNotBlank()) ": $body" else ""}")

/** Not signed in, or the login expired and couldn't be renewed. */
class AbsAuthException(message: String) : AbsException(message)

class TidarrException(message: String) : IOException(message)
