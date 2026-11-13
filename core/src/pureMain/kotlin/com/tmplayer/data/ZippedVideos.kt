package com.tmplayer.data

/**
 * How many videos a chat keeps inside ZIPs, for the picker's count (the same rule as the
 * listing, [MediaText.looksLikeVideoArchive]).
 *
 * Telegram's video filter never sees these: they are documents. So the counter reads one bounded
 * page of the chat's document search for "zip" ([PAGE]) and runs the names through here. A set of
 * pieces (`Name.1080p.zip.001`, `.002`, `.003`) is ONE video, as [SplitPieces.join] folds it in
 * the listing, and a lone release-named `.zip` as large as a video is one. A phone backup, a
 * module or an OTA zip is not.
 */
object ZippedVideos {

    /** The documents asked for in one page. A set bigger than this is counted by what the page held, hence "about". */
    const val PAGE = 100

    /** The search text: Telegram matches it against file names, `x.zip.001` included. */
    const val QUERY = "zip"

    /** A file's name and size, as the document search gave them. */
    data class Named(val name: String, val bytes: Long)

    /** The videos kept in ZIPs among [files]: one per set of pieces, one per lone video-sized zip. */
    fun count(files: List<Named>): Int {
        val sets = HashSet<String>()
        var lone = 0
        for (file in files) {
            if (!MediaText.looksLikeVideoArchive(file.name, file.bytes)) continue
            val piece = SplitPieces.pieceOf(file.name)
            if (piece == null) lone++ else sets.add(piece.key)
        }
        return sets.size + lone
    }
}
