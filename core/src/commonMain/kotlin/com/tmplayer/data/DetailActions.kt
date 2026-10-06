package com.tmplayer.data

/**
 * What the detail panel offers for one video, in the order it offers it.
 *
 * Worked out here, away from any screen, so the phone's sheet, the television's side pane and the
 * desktop's pane agree line for line, and so the rules that matter (a chat that restricts saving
 * content gets no way of keeping a copy) are tested once rather than trusted three times.
 */
enum class DetailAction {
    /** Carries on from the saved position. Offered instead of [Play] when there is one. */
    Resume,
    Play,

    /** From the first frame, forgetting the saved position. Only beside [Resume]. */
    StartOver,
    MarkWatched,
    MarkUnwatched,

    /** Fetch the file into Downloads. */
    Download,

    /** The whole file is in the watch cache already: move it into Downloads without fetching. */
    SaveToDownloads,

    /** Already in Downloads; says so, and closes the panel. */
    InDownloads,
    RemoveDownload,

    /** On the download queue at some stage; takes it off. */
    CancelDownload,

    /** Starts picking several videos at once, which is for downloading them. */
    SelectVideos,
    Share,
    OpenElsewhere,
    CopyLink,

    /** The chat the video was posted in, from a panel opened outside it (Home, search). */
    OpenChat,
}

/** Everything [DetailActions.of] needs to know about the video and where the panel was opened. */
data class DetailContext(
    /** False when the chat or the message restricts saving content. See [ContentProtection]. */
    val canBeSaved: Boolean,
    /** The saved position, 0 for none. */
    val resumeMs: Long = 0,
    /** On the Watched list. */
    val finished: Boolean = false,
    /** In the Downloads folder. */
    val inDownloads: Boolean = false,
    /** Whole in the watch cache. */
    val cached: Boolean = false,
    /** On the download queue, at any stage. */
    val downloading: Boolean = false,
    /** Opened from a chat's own grid, where picking several videos is possible. */
    val canSelect: Boolean = false,
    /** The platform has another app to hand the file to (the phone; not the television). */
    val canHandOff: Boolean = false,
    /** The platform has a clipboard worth a link (the phone and the desktop, not the television). */
    val hasClipboard: Boolean = false,
    /** Opened outside the video's chat, so going to that chat is a step somewhere. */
    val outsideChat: Boolean = false,
)

object DetailActions {

    fun of(context: DetailContext): List<DetailAction> = buildList {
        val resuming = context.resumeMs > 0
        if (resuming) {
            add(DetailAction.Resume)
            add(DetailAction.StartOver)
        } else {
            add(DetailAction.Play)
        }
        add(if (context.finished) DetailAction.MarkUnwatched else DetailAction.MarkWatched)
        when {
            // On the queue already: the one thing left to offer is taking it off again.
            context.downloading -> add(DetailAction.CancelDownload)
            // A copy the viewer made before the chat restricted saving is theirs to delete, so
            // these two stay even when saving is now refused.
            context.inDownloads -> {
                add(DetailAction.InDownloads)
                add(DetailAction.RemoveDownload)
            }
            // Restricted: streaming only, no copy of its own for the viewer.
            !context.canBeSaved -> Unit
            context.cached -> add(DetailAction.SaveToDownloads)
            else -> add(DetailAction.Download)
        }
        if (context.canSelect && context.canBeSaved) add(DetailAction.SelectVideos)
        // Both pass the file to somebody else's app, which is a copy leaving Telegram's hands, and
        // only once the whole file is here: half a file opened elsewhere stops after two minutes.
        val whole = context.inDownloads || context.cached
        if (context.canHandOff && context.canBeSaved && whole) {
            add(DetailAction.Share)
            add(DetailAction.OpenElsewhere)
        }
        // A restricted chat is one whose owner asked for its posts not to be passed on, and a
        // link is a way of passing one on, so it goes too.
        if (context.hasClipboard && context.canBeSaved) add(DetailAction.CopyLink)
        if (context.outsideChat) add(DetailAction.OpenChat)
    }

    /** True for every action that makes or hands over a copy, which a restricted chat never gets. */
    fun keepsACopy(action: DetailAction): Boolean = action in COPYING

    private val COPYING = setOf(
        DetailAction.Download,
        DetailAction.SaveToDownloads,
        DetailAction.SelectVideos,
        DetailAction.Share,
        DetailAction.OpenElsewhere,
        DetailAction.CopyLink,
    )
}
