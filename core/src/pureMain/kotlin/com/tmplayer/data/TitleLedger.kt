package com.tmplayer.data

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/**
 * What this device remembers about title reports (N1), apart from any screen: which pairs went
 * already (so the same pair is never sent twice), which the viewer was asked about already (so a
 * rename asks once per title, and "No thanks" is final), and the pairs waiting for a connection.
 *
 * Only hashes of what was sent are kept, never the text, except for the queue: those pairs the
 * viewer saw and agreed to send, kept at most [MAX_QUEUE], and gone the moment they are sent.
 * Every list is capped, oldest dropped first, so a long-lived install cannot grow it.
 *
 * Immutable: each change returns a new ledger. [decode] trusts nothing in the text it is given.
 */
class TitleLedger(
    /** Hashes of the pairs the inbox took, oldest first. */
    val sent: List<String> = emptyList(),
    /** Hashes of the pairs the viewer was asked about, answered or not, oldest first. */
    val asked: List<String> = emptyList(),
    /** Pairs agreed to and waiting for a connection, oldest first. */
    val queue: List<TitleReport> = emptyList(),
) {

    fun hasSent(report: TitleReport): Boolean = report.hash in sent

    fun isQueued(report: TitleReport): Boolean = queue.any { it.hash == report.hash }

    /**
     * Whether a rename to [report] should ask the viewer: once per pair, never for a pair that went
     * or waits, never again after the viewer was asked (whatever they answered).
     */
    fun shouldAsk(report: TitleReport): Boolean = report.hash !in asked && !hasSent(report) && !isQueued(report)

    /** The viewer was asked about [report] (or opened it themselves): not asked again. */
    fun withAsked(report: TitleReport): TitleLedger =
        if (report.hash in asked) this else TitleLedger(sent, (asked + report.hash).takeLast(MAX_ASKED), queue)

    /**
     * [report] waits for a connection. A pair that went or waits already is not added twice. Over
     * [MAX_QUEUE] the oldest pair is dropped, so the newest, which the viewer just agreed to, stays.
     */
    fun withQueued(report: TitleReport): TitleLedger {
        if (hasSent(report) || isQueued(report)) return this
        return TitleLedger(sent, asked, (queue + report).takeLast(MAX_QUEUE))
    }

    /** The inbox took [report]: remembered as sent, and off the queue. */
    fun withSent(report: TitleReport): TitleLedger {
        val rest = queue.filterNot { it.hash == report.hash }
        val hashes = if (report.hash in sent) sent else (sent + report.hash).takeLast(MAX_SENT)
        return TitleLedger(hashes, asked, rest)
    }

    /** [report] is off the queue without having gone (the inbox refused it for good). */
    fun withoutQueued(report: TitleReport): TitleLedger =
        if (!isQueued(report)) this else TitleLedger(sent, asked, queue.filterNot { it.hash == report.hash })

    fun encode(): String {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put("sent", JSONArray(sent))
        json.put("asked", JSONArray(asked))
        json.put("queue", JSONArray(queue.map { it.toJson() }))
        return json.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is TitleLedger && other.sent == sent && other.asked == asked && other.queue == queue

    override fun hashCode(): Int = (sent.hashCode() * 31 + asked.hashCode()) * 31 + queue.hashCode()

    companion object {
        const val VERSION = 1

        /** Pairs waiting for a connection; the 21st drops the oldest. */
        const val MAX_QUEUE = 20
        const val MAX_SENT = 500
        const val MAX_ASKED = 500

        /** A stored ledger bigger than this is not ours: it reads as empty. */
        private const val MAX_TEXT = 128 * 1024

        private val HASH = Regex("[0-9a-f]{16}")

        /** The ledger [text] holds; empty for null, blank, cut off, the wrong type or huge text. Bad entries are skipped, the rest kept. */
        fun decode(text: String?): TitleLedger {
            if (text.isNullOrBlank() || text.length > MAX_TEXT) return TitleLedger()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return TitleLedger()
            }
            return TitleLedger(
                sent = hashes(json.opt("sent")).takeLast(MAX_SENT),
                asked = hashes(json.opt("asked")).takeLast(MAX_ASKED),
                queue = reports(json.opt("queue")).distinctBy { it.hash }.takeLast(MAX_QUEUE),
            )
        }

        private fun hashes(value: Any?): List<String> {
            val array = value as? JSONArray ?: return emptyList()
            return array.mapNotNull { (it as? String)?.takeIf { hash -> HASH.matches(hash) } }.distinct()
        }

        private fun reports(value: Any?): List<TitleReport> {
            val array = value as? JSONArray ?: return emptyList()
            return array.mapNotNull { (it as? String)?.let(TitleReport::parse) }
        }
    }
}
