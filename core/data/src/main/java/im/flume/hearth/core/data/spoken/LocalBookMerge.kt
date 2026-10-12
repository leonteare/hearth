package im.flume.hearth.core.data.spoken

/** Covers downloaded for a match are named "<id>-online-….img", apart from the book's own. */
fun isOnlineCoverPath(path: String?): Boolean = path?.substringAfterLast('/')?.substringAfterLast('\\')?.contains("-online") == true

object LocalBookMerge {
    /**
     * A freshly scanned book, keeping what happened to it since: removed, added to the server, and
     * any details found online. Matched books keep their title and author even when the files
     * change; only what the files say ([LocalBookEntity.fileTitle]) is updated. A downloaded cover
     * stays until the files bring a cover of their own.
     */
    fun keepUserState(scanned: LocalBookEntity, current: LocalBookEntity): LocalBookEntity {
        val matched = current.matchState == LocalMatchState.AUTO.name || current.matchState == LocalMatchState.CONFIRMED.name
        return scanned.copy(
            hidden = current.hidden, serverItemId = current.serverItemId, uploadState = current.uploadState, uploadError = current.uploadError,
            title = if (matched) current.title else scanned.title,
            author = if (matched) current.author else scanned.author,
            narrator = current.narrator, description = current.description, publishedYear = current.publishedYear, series = current.series,
            matchState = current.matchState, matchSource = current.matchSource, matchCheckedAt = current.matchCheckedAt,
            coverPath = scanned.coverPath ?: current.coverPath?.takeIf { isOnlineCoverPath(it) },
        )
    }
}
