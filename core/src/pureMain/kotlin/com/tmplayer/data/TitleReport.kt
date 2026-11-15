package com.tmplayer.data

import com.tmplayer.json.JSONObject

/**
 * What may leave the device when a viewer says "this title is wrong" (N1): the video's file name
 * after [scrub], the title the viewer chose for it, and the chat's name only when they ticked it.
 * Nothing else: no message or chat id, no caption, no size, no account. The sheet shows exactly
 * [toJson] before anything is sent, and the viewer can drop the report.
 *
 * [hash] is for the inbox and the queue: the same pair reported twice (twice by one viewer, or by
 * two) has one hash, whatever the case, spacing or chat, so it is stored once.
 *
 * Built by [of], which returns null when there is nothing worth sending (no name left after the
 * scrub, or no title), and read back by [parse], which trusts nothing in the text it is given.
 */
class TitleReport private constructor(
    /** The original file name, scrubbed and capped. Never empty. */
    val fileName: String,
    /** The title the viewer chose. Never empty. */
    val title: String,
    /** The chat's name, only when the viewer ticked it. */
    val chatName: String?,
) {

    /** The same for the same [fileName] and [title], whatever their case or spacing; the chat is not in it. */
    val hash: String get() = hashOf(fileName, title)

    /**
     * A short code for the pair, NAME-7K3Q, that the inbox works out from the same hash and
     * answers with: a viewer on a television, who cannot type, reads it out instead. It is a
     * label for saying aloud, never a way to find the report (two pairs may share one).
     */
    val code: String get() = codeOf(hash)

    /** One JSON object, the whole of what is sent. */
    fun toJson(): String {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put("name", fileName)
        json.put("title", title)
        if (chatName != null) json.put("chat", chatName)
        json.put("hash", hash)
        return json.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is TitleReport && other.fileName == fileName && other.title == title && other.chatName == chatName

    override fun hashCode(): Int = (fileName.hashCode() * 31 + title.hashCode()) * 31 + (chatName?.hashCode() ?: 0)

    override fun toString(): String = "TitleReport(${toJson()})"

    companion object {
        const val VERSION = 1

        /** A file name is 255 characters at most on every file system; more is not a name. */
        const val MAX_NAME = 255
        const val MAX_TITLE = 200
        const val MAX_CHAT = 100

        /** The most a stored or received report text is read from: a bigger body is not one of ours. */
        const val MAX_JSON = 2_048

        /**
         * The report for [fileName] renamed to [title], or null when there is nothing to send: a
         * title that is blank, or a name that is blank once [scrub] has taken the private parts
         * off. [chatName] goes in only when [includeChat] (the viewer ticked it) and is not blank.
         */
        fun of(fileName: String, title: String, chatName: String? = null, includeChat: Boolean = false): TitleReport? {
            // The patterns below are not linear on a very long run (an e-mail address starts anywhere),
            // so only a generous head of each text is ever scrubbed: a 100,000 character name took
            // 89 s. The head is four times the cap, so what the scrub removes cannot push the cap's
            // worth of text out of reach.
            val name = capped(scrub(headOf(fileName, MAX_NAME * HEAD_FACTOR)), MAX_NAME)
            val chosen = capped(scrub(headOf(title, MAX_TITLE * HEAD_FACTOR)), MAX_TITLE)
            if (name.isEmpty() || chosen.isEmpty()) return null
            val chat = if (includeChat) chatName?.let { capped(scrub(headOf(it, MAX_CHAT * HEAD_FACTOR)), MAX_CHAT) }?.ifEmpty { null } else null
            return TitleReport(name, chosen, chat)
        }

        /**
         * [text] without what identifies a person: phone numbers, e-mail addresses, t.me links and
         * @usernames (each gone, or "@user" for a username so the name still shows where it was),
         * with the spaces they leave tidied. Runs before the viewer sees the report, and again on
         * the receiving side, so what arrives is scrubbed whatever sent it. Doing it twice changes
         * nothing more.
         *
         * A phone number is ten digits or more with the usual separators between them, or a "+"
         * and eight or more; a year and a resolution ("2024 1080p") are not one. A username that
         * holds a title ("@CC_Night_Train") goes whole: the viewer's own title says what it was.
         */
        fun scrub(text: String): String {
            if (text.isEmpty()) return text
            var out = text.replace(EMAIL, " ").replace(TELEGRAM_LINK, " ")
            out = out.replace(PHONE_CANDIDATE) { if (digitsIn(it.value.replace(DATE, "")) >= minDigits(it.value)) " " else it.value }
            out = out.replace(USERNAME, "@user")
            return out.replace(SPACES, " ").trim()
        }

        /** [fileName] and [title] as the inbox counts them: case and spacing do not make two reports. */
        internal fun hashOf(fileName: String, title: String): String {
            val key = normalised(fileName) + "\u0000" + normalised(title)
            // FNV-1a over the UTF-8 bytes, 64 bits: a key for dedupe, not a secret.
            var hash = -3750763034362895579L // 0xcbf29ce484222325
            for (byte in key.encodeToByteArray()) {
                hash = (hash xor (byte.toLong() and 0xff)) * 1099511628211L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }

        /**
         * The report [text] holds (as [toJson] writes it), or null when it is not one: not JSON, the
         * wrong type in a field, a name or a title missing, a body past [MAX_JSON]. Fields are
         * scrubbed and capped again and the hash is worked out afresh, never taken from the text.
         */
        fun parse(text: String): TitleReport? {
            if (text.length > MAX_JSON) return null
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return null
            }
            val name = json.opt("name") as? String ?: return null
            val title = json.opt("title") as? String ?: return null
            val chat = json.opt("chat") as? String
            return of(name, title, chat, includeChat = chat != null)
        }

        /** No 0, 1, I, L or O, as the log inbox's codes: a code read aloud or off a television is not mistaken. */
        const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /** NAME-XXXX for a [hash] of 16 hex digits: the number read in base 31, last four digits. A hash that is not hex gives "NAME-2222". */
        internal fun codeOf(hash: String): String {
            var value = hash.toULongOrNull(16) ?: 0uL
            val base = CODE_ALPHABET.length.toULong()
            val out = CharArray(4)
            for (i in 3 downTo 0) {
                out[i] = CODE_ALPHABET[(value % base).toInt()]
                value /= base
            }
            return "NAME-" + out.concatToString()
        }

        private fun normalised(text: String): String =
            text.trim().lowercase().replace(SPACES, " ")

        private const val HEAD_FACTOR = 4

        /** The first [max] characters of [text], never ending on half a surrogate pair. */
        private fun headOf(text: String, max: Int): String {
            if (text.length <= max) return text
            return text.substring(0, if (text[max - 1].isHighSurrogate()) max - 1 else max)
        }

        /** [text] cut to [max] characters, not through a surrogate pair, and trimmed. */
        private fun capped(text: String, max: Int): String {
            val trimmed = text.trim()
            if (trimmed.length <= max) return trimmed
            var end = max
            if (trimmed[end - 1].isHighSurrogate()) end--
            return trimmed.substring(0, end).trim()
        }

        private fun digitsIn(text: String): Int = text.count { it.isDigit() }

        private fun minDigits(candidate: String): Int = if (candidate.trimStart().startsWith("+")) 8 else 10

        private val SPACES = Regex("""\s+""")

        // A run that starts and ends on a digit, with the separators phone numbers are written
        // with between: "+91 98765 43210", "(555) 123-4567", "98765.43210".
        private val PHONE_CANDIDATE = Regex("""(?<![\p{L}\p{N}])\+?\(?\p{Nd}[\p{Nd}\s().\-]{6,}\p{Nd}(?![\p{L}\p{N}])""")

        // "2024-10-14" and "14.10.2024" in a daily show's name are dates, not digits to scrub.
        private val DATE = Regex("""(?:19|20)\d{2}[ .\-]\d{2}[ .\-]\d{2}|\d{2}[ .\-]\d{2}[ .\-](?:19|20)\d{2}""")

        private val EMAIL = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}.\-]+\.\p{L}{2,}""")

        private val TELEGRAM_LINK = Regex("""(?:https?://)?(?:t\.me|telegram\.me|telegram\.dog)/\S*""", RegexOption.IGNORE_CASE)

        private val USERNAME = Regex("""@[\p{L}\p{N}_]{2,}""")
    }
}
