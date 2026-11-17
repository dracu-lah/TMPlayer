package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * Why TDLib would not start, in words the viewer can act on. The one place the sign in failure
 * screen's sentence is chosen, for both ways TDLib fails to start: its native library refusing to
 * load ([describe]) and TDLib refusing its parameters ([describeRejected]).
 *
 * The cases seen so far, each with its own sentence and the raw text left for the log:
 *  - [Kind.Blocked]: Windows Smart App Control or an Application Control policy refused one of
 *    TDLib's unsigned DLLs (#27), as it does libmpv's (HX36, `VideoEngineFailure`).
 *  - [Kind.SystemTooOld]: a Linux older than the glibc and libstdc++ the prebuilt TDLib was linked
 *    against, `version 'GLIBCXX_3.4.32' not found` (#30). The floor is glibc 2.35, see
 *    `desktop/packaging/linux/glibc-floor.sh` and `natives/linux-2-35/PINS.txt`.
 *  - [Kind.FolderUnusable]: TDLib could not make its files or database directory, a drive that was
 *    unplugged or a folder that is read only (#35).
 *
 * The messages are outside data: anything, of any length, or nothing. Nothing here throws.
 */
object StartFailures {

    enum class Kind { Blocked, SystemTooOld, FolderUnusable, Other }

    private val BLOCKED = Regex(
        """application control|smart app control|0x800711c7|blocked by group policy|software restriction polic""",
        RegexOption.IGNORE_CASE,
    )

    /** The dynamic loader's `version 'GLIBCXX_3.4.32' not found`, with either quote style. */
    private val TOO_OLD = Regex("""version\s+\W?(GLIBC|GLIBCXX|CXXABI)_[0-9.]+\W?\s+not found""", RegexOption.IGNORE_CASE)

    /** TDLib's `Can't init files directory "<path>"` and `Can't create directory "<path>"`. */
    private val FOLDER = Regex("""can't (init|create|open)( files| database)? director""", RegexOption.IGNORE_CASE)

    /** The first quoted path in TDLib's message. */
    private val QUOTED = Regex("\"([^\"\\n]{1,$MAX_PATH})\"")

    /** Which of the known cases [message] reads as. */
    fun kind(message: String?): Kind {
        val text = message.orEmpty().take(MAX_SCAN)
        return when {
            text.isBlank() -> Kind.Other
            BLOCKED.containsMatchIn(text) -> Kind.Blocked
            TOO_OLD.containsMatchIn(text) -> Kind.SystemTooOld
            FOLDER.containsMatchIn(text) -> Kind.FolderUnusable
            else -> Kind.Other
        }
    }

    /** The folder TDLib named in [message], or null when it named none. */
    fun folder(message: String?): String? =
        QUOTED.find(message.orEmpty().take(MAX_SCAN))?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The sentence for a client that could not start at all ([error] escaped TDLib's start, the
     * native library load). Every message in the chain of causes is read, since the one that names
     * the reason is not always the outermost. Never empty, never throws.
     */
    fun describe(error: Throwable?): String {
        val messages = causes(error).mapNotNull { it.message?.takeIf(String::isNotBlank) }
        val known = messages.firstNotNullOfOrNull { message -> sentence(kind(message), message) }
        if (known != null) return known
        val first = messages.firstOrNull() ?: error?.let { it::class.simpleName }.orEmpty()
        return L.errorsStartFailed(first.take(MAX_SHOWN))
    }

    /** The sentence for TDLib answering `setTdlibParameters` with [message]. Never empty. */
    fun describeRejected(message: String?): String =
        sentence(kind(message), message.orEmpty()) ?: L.errorsStartRejected(message.orEmpty().take(MAX_SHOWN))

    private fun sentence(kind: Kind, message: String): String? = when (kind) {
        Kind.Blocked -> L.errorsStartBlocked
        Kind.SystemTooOld -> L.errorsStartSystemTooOld
        Kind.FolderUnusable -> folder(message)?.let { L.errorsStartFolder(it) } ?: L.errorsStartFolderUnnamed
        Kind.Other -> null
    }

    private fun causes(error: Throwable?): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var at = error
        while (at != null && seen.size < MAX_CAUSES && seen.none { it === at }) {
            seen += at
            at = at.cause
        }
        return seen
    }

    private const val MAX_CAUSES = 8
    private const val MAX_SCAN = 4_000
    private const val MAX_PATH = 1_024
    private const val MAX_SHOWN = 600
}
