package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What a chat with no playable videos does hold (U18): photos, audio and files that are not videos
 * or recognised archives. Said under "No playable videos", so a chat of pictures or documents reads
 * as what it is rather than as a fault. A kind the count could not give is left out, not guessed.
 *
 * @param photos pictures; null when the count did not come back
 * @param audio music and voice notes
 * @param files documents of any other kind
 */
data class ChatHolds(val photos: Int? = null, val audio: Int? = null, val files: Int? = null) {

    /** One sentence per kind present, in the order photos, audio, files; empty when the chat holds none of them. */
    fun sentences(): List<String> = buildList {
        photos?.takeIf { it > 0 }?.let { add(L.browseHoldsPhotos(count = it)) }
        audio?.takeIf { it > 0 }?.let { add(L.browseHoldsAudio(count = it)) }
        files?.takeIf { it > 0 }?.let { add(L.browseHoldsFiles(count = it)) }
    }

    /** [message] with what the chat holds under it, or [message] unchanged when it holds nothing worth saying. */
    fun under(message: String): String {
        val said = sentences()
        return if (said.isEmpty()) message else message + "\n\n" + said.joinToString(" ")
    }

    companion object {
        /** TDLib answers -1 for a count it cannot give without the server; that is "unknown", never "none". */
        fun known(count: Int?): Int? = count?.takeIf { it >= 0 }
    }
}
