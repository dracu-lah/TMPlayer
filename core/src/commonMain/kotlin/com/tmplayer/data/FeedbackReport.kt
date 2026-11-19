package com.tmplayer.data

import com.tmplayer.json.JSONObject
import kotlin.random.Random

/**
 * What leaves the device when a viewer sends "Report a problem" or "Request a feature" (HX64):
 * what they typed ([message], and [contact] only when they gave one), the code of the log report
 * they chose to add ([logs], only when ticked), and the same facts the GitHub route fills in
 * ([Feedback.Facts]: version, platform, device, app language). Nothing else: no account, no chat,
 * no file name. The confirm step shows exactly [lines] before anything is sent.
 *
 * The inbox is `workers/log-inbox` (`POST /v1/feedback`), which checks every field again. [id] is
 * drawn once per draft and sent on every try, so the inbox files one draft once however often it
 * arrives (a double press, a retry, the app killed part way).
 */
class FeedbackReport private constructor(
    val id: String,
    val kind: Kind,
    val message: String,
    val contact: String?,
    val logs: String?,
    val facts: Feedback.Facts,
) {

    enum class Kind(val wire: String) {
        Problem("problem"),
        Feature("feature"),
        ;

        companion object {
            fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** The one JSON object sent, the whole of it. */
    fun toJson(): String {
        val app = JSONObject()
            .put("version", facts.version)
            .put("platform", facts.platform.option.ifEmpty { "other" })
            .put("device", facts.device)
            .put("language", facts.language)
        val json = JSONObject()
            .put("v", VERSION)
            .put("id", id)
            .put("kind", kind.wire)
            .put("message", message)
        if (contact != null) json.put("contact", contact)
        if (logs != null) json.put("logs", logs)
        json.put("app", app)
        return json.toString()
    }

    override fun equals(other: Any?): Boolean = other is FeedbackReport && other.toJson() == toJson()

    override fun hashCode(): Int = toJson().hashCode()

    override fun toString(): String = "FeedbackReport($id, ${kind.wire}, ${message.length} chars)"

    /** Why a draft cannot be sent as it stands. */
    enum class Problem { Empty, TooLong, ContactTooLong }

    companion object {
        const val VERSION = 1

        /** The inbox's caps (feedback.ts): a longer message is refused there, so it is never sent. */
        const val MAX_MESSAGE = 4_000
        const val MAX_CONTACT = 120

        /** The most of a stored or received report text that is read: a bigger one is not ours. */
        const val MAX_JSON = 16 * 1024

        private val ID = Regex("[0-9a-f]{32}")
        private val LOG_CODE = Regex("TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}")

        // Control characters, but not the line break and the tab a message may hold; and the
        // invisible marks that turn text around.
        private val CONTROL = Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u2028-\\u202e\\u2066-\\u2069\\ufeff]")
        private val SPACES = Regex("\\s+")
        private val TRAILING = Regex("[ \\t]+$", RegexOption.MULTILINE)
        private val BLANK_RUN = Regex("\\n{4,}")

        /** A fresh draft id: 32 hex digits, random, nothing about the device in it. */
        fun newId(random: Random = Random.Default): String =
            random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        /** The message as the inbox keeps it: line breaks kept (two blank lines at most), no control characters, trimmed. */
        fun cleanMessage(text: String): String = text
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace(CONTROL, "")
            .replace(TRAILING, "")
            .replace(BLANK_RUN, "\n\n\n")
            .trim()

        /** A one line field: no control characters, spaces tidied, trimmed. Not capped: see [problem]. */
        fun cleanLine(text: String): String = text.take(MAX_JSON).replace(CONTROL, "").replace(SPACES, " ").trim()

        /** What stops [message] and [contact] from being sent, or null when they can go. */
        fun problem(message: String, contact: String): Problem? {
            val m = cleanMessage(message)
            return when {
                m.isEmpty() -> Problem.Empty
                m.length > MAX_MESSAGE -> Problem.TooLong
                cleanLine(contact).length > MAX_CONTACT -> Problem.ContactTooLong
                else -> null
            }
        }

        /**
         * The report, or null when [problem] says it cannot go or [id] is not one. [logs] goes in
         * only when it is a log inbox code. Nothing typed is ever cut: too long is null, never half.
         */
        fun of(id: String, kind: Kind, message: String, contact: String?, logs: String?, facts: Feedback.Facts): FeedbackReport? {
            if (!ID.matches(id)) return null
            if (problem(message, contact.orEmpty()) != null) return null
            val reply = contact?.let { cleanLine(it) }?.ifEmpty { null }
            val code = logs?.takeIf { LOG_CODE.matches(it) }
            val clean = Feedback.Facts(
                version = cleanLine(facts.version).take(40),
                platform = facts.platform,
                device = cleanLine(facts.device).take(160),
                language = cleanLine(facts.language).take(20),
            )
            return FeedbackReport(id, kind, cleanMessage(message), reply, code, clean)
        }

        /** The report [text] holds (as [toJson] writes it), or null when it is not one. Trusts nothing in it. */
        fun parse(text: String): FeedbackReport? {
            if (text.length > MAX_JSON) return null
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return null
            }
            if ((json.opt("v") as? Number)?.toDouble() != VERSION.toDouble()) return null
            val id = json.opt("id") as? String ?: return null
            val kind = Kind.of(json.opt("kind") as? String) ?: return null
            val message = json.opt("message") as? String ?: return null
            val app = json.opt("app") as? JSONObject
            fun fact(key: String): String = app?.opt(key) as? String ?: ""
            val platformText = fact("platform")
            val facts = Feedback.Facts(
                version = fact("version"),
                platform = Feedback.Platform.entries.firstOrNull { it.option.isNotEmpty() && it.option == platformText } ?: Feedback.Platform.Other,
                device = fact("device"),
                language = fact("language"),
            )
            return of(id, kind, message, json.opt("contact") as? String, json.opt("logs") as? String, facts)
        }
    }
}

/**
 * A report being written, kept on the device so nothing typed is lost: closed half way, no
 * connection, the app killed. [pending] is the exact JSON the viewer confirmed and pressed Send
 * on, waiting to go; null while it is still being written. [logsCode] is the log report already
 * sent for it, so a retry does not send the logs twice.
 */
data class FeedbackDraft(
    val kind: FeedbackReport.Kind = FeedbackReport.Kind.Problem,
    val message: String = "",
    val contact: String = "",
    val includeLogs: Boolean = false,
    val id: String = FeedbackReport.newId(),
    val logsCode: String? = null,
    val pending: String? = null,
) {

    /** The log report's code that goes with the words: only while "Add my logs" is ticked. */
    val logsToSend: String? get() = logsCode.takeIf { includeLogs }

    /** Nothing worth keeping. */
    val isBlank: Boolean get() = message.isBlank() && contact.isBlank() && pending == null

    fun encode(): String {
        val json = JSONObject()
            .put("kind", kind.wire)
            .put("message", message)
            .put("contact", contact)
            .put("logs", includeLogs)
            .put("id", id)
        if (logsCode != null) json.put("logsCode", logsCode)
        if (pending != null) json.put("pending", pending)
        return json.toString()
    }

    companion object {
        /** Twice the caps and the pending report: a stored draft bigger than this is not ours. */
        private const val MAX_STORED = 64 * 1024

        /** The draft [text] holds, or null when there is none or it is not one. Fields that are wrong fall back. */
        fun decode(text: String?): FeedbackDraft? {
            if (text.isNullOrBlank() || text.length > MAX_STORED) return null
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return null
            }
            val id = (json.opt("id") as? String)?.takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: FeedbackReport.newId()
            val pending = (json.opt("pending") as? String)?.takeIf { FeedbackReport.parse(it)?.id == id }
            return FeedbackDraft(
                kind = FeedbackReport.Kind.of(json.opt("kind") as? String) ?: FeedbackReport.Kind.Problem,
                message = (json.opt("message") as? String)?.take(FeedbackReport.MAX_MESSAGE * 2).orEmpty(),
                contact = (json.opt("contact") as? String)?.take(FeedbackReport.MAX_CONTACT * 2).orEmpty(),
                includeLogs = json.opt("logs") as? Boolean ?: false,
                id = id,
                logsCode = (json.opt("logsCode") as? String)?.takeIf { Regex("TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}").matches(it) },
                pending = pending,
            )
        }
    }
}

/**
 * How the inbox's answer to feedback is read: `{"code":"FB-7K3Q-M4XP","duplicate":false}`. The same
 * outcomes as a title report's ([TitleUpload.Outcome]). Reads text from outside, so it never throws.
 */
object FeedbackUpload {

    const val MAX_BYTES = FeedbackReport.MAX_JSON

    /** How long the inbox keeps feedback, as the Worker's own constant. */
    const val KEEP_DAYS = 30

    private const val MAX_REPLY_CHARS = 4_096

    private val CODE_FIELD = Regex(""""code"\s*:\s*"(FB-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4})"""")
    private val DUPLICATE = Regex(""""duplicate"\s*:\s*true""")

    fun outcome(status: Int, body: String?, retryAfter: String? = null): TitleUpload.Outcome {
        if (status == 201 || status == 200) {
            val text = body?.take(MAX_REPLY_CHARS)
            val code = text?.let { CODE_FIELD.find(it)?.groupValues?.getOrNull(1) }
            if (code != null) return TitleUpload.Outcome.Sent(code, duplicate = DUPLICATE.containsMatchIn(text))
        }
        if (status == 429) return TitleUpload.Outcome.SlowDown(retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, 3_600) ?: 60)
        return TitleUpload.Outcome.Failed(status)
    }
}
