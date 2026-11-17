package com.tmplayer.platform

/**
 * The text work of a kept log (iOS's KeptLog), apart from its files so it can be checked anywhere:
 * TDLib's own lines made to read like the app's, and the two merged in the order things happened.
 *
 * A kept line starts with its moment as "yyyy-MM-dd HH:mm:ss.SSS" ([STAMP_LENGTH] characters), so
 * the text sorts by time; the process and thread follow, then the line as the app logged it.
 */
object KeptLogLines {
    const val STAMP_LENGTH = 23

    // "[ 2][t 3][1763241031.664125060][AuthManager.cpp:123][#1][!Td]\tLost authorization": the
    // level, the thread, the moment in seconds and the place, then any tags, then the message.
    private val TDLIB = Regex("""^\[\s*(\d+)]\[t\s*(\d+)]\[(\d+)\.(\d+)]\[([^\]]*)]((?:\[[^\]]*])*)\s*(.*)$""")

    /**
     * TDLib's [raw] lines as kept lines, with [stamp] writing a moment in milliseconds since 1970.
     * A line that does not start like one of TDLib's (the rest of a message that ran over a line)
     * takes the moment of the line before it, and is dropped when nothing came before.
     */
    fun fromTdlib(raw: List<String>, stamp: (Long) -> String): List<String> {
        val out = ArrayList<String>(raw.size)
        var last: String? = null
        for (line in raw) {
            if (line.isBlank()) continue
            val match = TDLIB.matchEntire(line)
            if (match == null) {
                last?.let { out += "$it $line" }
                continue
            }
            val (level, thread, seconds, fraction, place, _, message) = match.destructured
            val millis = (seconds.toLongOrNull() ?: continue) * 1000 + fraction.padEnd(3, '0').take(3).toInt()
            val head = "${stamp(millis)} tdlib t$thread"
            out += "$head ${letter(level)}/TDLib: [$place] $message"
            last = head
        }
        return out
    }

    /**
     * The last [max] of [app] and [tdlib] together, in time order. Lines of the same moment keep
     * the order they came in, the app's first.
     */
    fun merge(app: List<String>, tdlib: List<String>, max: Int): List<String> =
        (app.takeLast(max) + tdlib.takeLast(max)).sortedBy { it.take(STAMP_LENGTH) }.takeLast(max)

    // TDLib's levels: 0 fatal, 1 error, 2 warning, 3 info, 4 debug, 5 and up verbose.
    private fun letter(level: String): Char = when (level.toIntOrNull()) {
        0 -> 'F'
        1 -> 'E'
        2 -> 'W'
        3 -> 'I'
        4 -> 'D'
        else -> 'V'
    }
}
