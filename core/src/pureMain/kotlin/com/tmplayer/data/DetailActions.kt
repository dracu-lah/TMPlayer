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

    /** Puts this one video on Watch later, without starring its chat. See [WatchLaterStore]. */
    AddWatchLater,
    RemoveWatchLater,

    /**
     * Takes the video out of History: its saved place and its watched tick, every copy of it
     * ([HistoryEdits]). Offered while it is part way through.
     */
    RemoveFromHistory,

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

    /** "Not the same video": takes this copy out of the tile it was folded into. See [VideoCorrection]. */
    SplitCopy,

    /** Undoes [SplitCopy]. */
    JoinCopies,

    /** "Wrong match": the provider's poster and words go, the file's own name and picture stay. */
    WrongMatch,

    /** Undoes [WrongMatch]. */
    UseMatch,

    /** The title the viewer wants to see, which grouping reads too. */
    Rename,

    /** "Videos in the comments": the videos posted under a channel post (CP81). */
    CommentVideos,

    /** Copies the finished download out: the phone's gallery, or a folder on the desktop (CP82). */
    ExportCopy,
}

/** Everything [DetailActions.of] needs to know about the video and where the panel was opened. */
data class DetailContext(
    /** False when the chat or the message restricts saving content. See [ContentProtection]. */
    val canBeSaved: Boolean,
    /** The saved position, 0 for none. */
    val resumeMs: Long = 0,
    /** On the Watched list. */
    val finished: Boolean = false,
    /** On Watch later; null where the panel does not offer that list at all. */
    val watchLater: Boolean? = null,
    /** In the Downloads folder. */
    val inDownloads: Boolean = false,
    /**
     * Not this file but another copy of the same video is in the Downloads folder, so the video is
     * kept already and a second size of it is not what anybody asked for.
     */
    val copyInDownloads: Boolean = false,
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
    /** The panel can take the viewer's corrections ([VideoCorrection]); off where nothing would redraw. */
    val canCorrect: Boolean = false,
    /** The tile stands for several copies of one video. */
    val grouped: Boolean = false,
    /** The viewer set this file apart already. */
    val separated: Boolean = false,
    /** A provider's poster and words are shown for it. */
    val matched: Boolean = false,
    /** The viewer turned the provider's match down already. */
    val wrongMatch: Boolean = false,
    /** How many comments the post has (a channel post with a discussion group), 0 for none. */
    val comments: Int = 0,
    /** The platform can copy a finished download out (the phone's gallery, a desktop folder). */
    val canExport: Boolean = false,
    /** The panel can take the video out of History ([DetailAction.RemoveFromHistory]). */
    val canRemoveFromHistory: Boolean = false,
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
        // Nothing is copied, only remembered, so a chat that restricts saving keeps this too.
        context.watchLater?.let { add(if (it) DetailAction.RemoveWatchLater else DetailAction.AddWatchLater) }
        when {
            // On the queue already: the one thing left to offer is taking it off again.
            context.downloading -> add(DetailAction.CancelDownload)
            // A copy the viewer made before the chat restricted saving is theirs to delete, so
            // these two stay even when saving is now refused.
            context.inDownloads -> {
                add(DetailAction.InDownloads)
                add(DetailAction.RemoveDownload)
            }
            // Said, not offered again. No Remove here: that copy's own page is where it goes.
            context.copyInDownloads -> add(DetailAction.InDownloads)
            // Restricted: streaming only, no copy of its own for the viewer.
            !context.canBeSaved -> Unit
            context.cached -> add(DetailAction.SaveToDownloads)
            else -> add(DetailAction.Download)
        }
        // Both pass the file to somebody else's app, which is a copy leaving Telegram's hands, and
        // only once the whole file is here: half a file opened elsewhere stops after two minutes.
        val whole = context.inDownloads || context.cached
        if (context.canHandOff && context.canBeSaved && whole) {
            add(DetailAction.Share)
            add(DetailAction.OpenElsewhere)
        }
        if (context.canExport && KeepRules.mayExport(context.canBeSaved, context.inDownloads)) add(DetailAction.ExportCopy)
        if (context.comments > 0) add(DetailAction.CommentVideos)
        if (context.outsideChat) add(DetailAction.OpenChat)
        // Only for a video part way through: a finished one leaves History by Mark as unwatched,
        // and two buttons doing the same would only ask which to press.
        if (context.canRemoveFromHistory && resuming) add(DetailAction.RemoveFromHistory)
        // Last, being repairs rather than things to do with the video. Each one has its undo.
        if (context.canCorrect) {
            when {
                context.separated -> add(DetailAction.JoinCopies)
                context.grouped -> add(DetailAction.SplitCopy)
            }
            when {
                context.wrongMatch -> add(DetailAction.UseMatch)
                context.matched -> add(DetailAction.WrongMatch)
            }
            add(DetailAction.Rename)
        }
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
        DetailAction.ExportCopy,
    )
}
