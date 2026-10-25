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

/** Something went wrong talking to a Sonos speaker on the network. */
open class SonosException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The speaker understood the request and refused it: UPnP answers a bad action with HTTP 500 and an
 * error code in the body, so [errorCode] is what actually went wrong (714 means it didn't recognise the
 * audio type, which is what you get when a stream URL is sent without any metadata describing it).
 */
class SonosUpnpException(val errorCode: Int, val description: String) :
    SonosException(sonosFaultMessage(errorCode, description))

/** Plain words for the UPnP error codes a speaker actually sends back. */
private fun sonosFaultMessage(code: Int, description: String): String = when (code) {
    402 -> "The speaker didn't understand that request (error 402)"
    701 -> "The speaker can't do that just now (error 701)"
    711 -> "The speaker wouldn't jump to that spot in the track (error 711)"
    714 -> "The speaker didn't recognise that audio (error 714)"
    718 -> "The speaker has nothing loaded to play (error 718)"
    else -> {
        val detail = description.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        "The speaker turned that down (error $code$detail)"
    }
}
