package im.flume.hearth.util

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

/** A short, safe-to-show description of [e] for error messages. */
fun userMessage(e: Throwable, fallback: String = e.javaClass.simpleName): String =
    scrubSecrets(e.message?.takeIf { it.isNotBlank() } ?: fallback)
