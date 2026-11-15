package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one [TitleSender] a process has, and the entry points the screens use (N1): the video menu's
 * "Wrong title?" and the prompt after a rename. A build with no inbox address ([LogUpload.titlesUrl]
 * null: forks, CI) has none, so [available] is false, the menu line is not offered and a rename
 * asks nothing.
 *
 * Lives in the process, not in a screen: the rename dialog closes its page the moment it saves, and
 * the question that follows must outlast it. The screens' host (`TitleReportHost`) draws [sender]'s state.
 */
object TitleReports {

    @Volatile
    var sender: TitleSender? = null
        private set

    /** How long after start the waiting pairs are tried: the app is up and the line settled. */
    private const val FLUSH_DELAY_MS = 60_000L

    /** Starts the process's sender over [store] and the build's [address]; null or an unusable address leaves none. Safe to call again. */
    fun install(store: TitleLedgerStore, address: String?) {
        val url = LogUpload.titlesUrl(address)
        sender?.close()
        sender = url?.let { target -> TitleSender(store) { text -> LogUploader.sendTitle(target, text) } }
    }

    val available: Boolean get() = sender != null

    /**
     * Whether [item]'s menu offers "Wrong title?": there is an inbox, and the file's name is
     * something to send once private parts are taken out of it.
     */
    fun canReport(item: MediaItem): Boolean = available && TitleReport.of(item.fileName, PLACEHOLDER) != null

    /**
     * The menu's "Wrong title?" for a video the viewer has renamed already: the review of the
     * file name and [chosenTitle], even when this pair was asked about before.
     */
    fun open(item: MediaItem, chosenTitle: String, chatName: String?) {
        sender?.open(Background.scope, item.fileName, chosenTitle, chatName, afterRename = false)
    }

    /**
     * After a rename to [newTitle] is saved. By default it asks once per pair whether to tell the
     * developer, and nothing for a reset, for a title the app showed already ([shownBefore]) or a
     * pair asked about before. [force] is the menu's "Wrong title?" for a video not yet renamed:
     * the viewer asked for it, so it opens even for a pair asked about before.
     */
    fun askAfterRename(item: MediaItem, shownBefore: String, newTitle: String?, chatName: String?, force: Boolean = false) {
        val title = newTitle?.trim().orEmpty()
        if (title.isEmpty()) return
        // The same title as the app showed says nothing about a wrong reading, forced or not.
        if (title.equals(shownBefore.trim(), ignoreCase = true)) return
        sender?.open(Background.scope, item.fileName, title, chatName, afterRename = !force)
    }

    /** Tries the pairs waiting for a connection once, a minute after start, and never on its own again. Skipped in safe mode by the caller. */
    fun flushSoon() {
        val current = sender ?: return
        Background.scope.launch {
            delay(FLUSH_DELAY_MS)
            runCatching { current.flush() }
        }
    }

    private const val PLACEHOLDER = "x"
}
