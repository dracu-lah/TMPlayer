package com.tmplayer.player

import com.tmplayer.data.OpeningStall

/**
 * What the "this video hasn't started" sheet offers (U1), decided apart from the screen so the phone
 * and the television show the same buttons in the same order and a test can check it.
 *
 * The sheet comes only when nothing has arrived for [OpeningStall.STALL_MS]: bytes that flow slowly
 * start the count again with every one ([OpeningStall.stalled]), so a slow line never reads as
 * "Telegram has not started sending this", it keeps the loader and its speed note.
 */
object StalledSheet {

    enum class Action {
        /** The first and the focused one: reopens the connections and asks again. */
        TryAgain,

        /** The video joins the download queue and the player steps back. Where the chat allows a copy. */
        DownloadFirst,

        /** Telegram's own lighter copy (CP80). Only while the line is up and Telegram is the slow one. */
        LighterCopy,

        /** PR4's handover to VLC or MX Player. See [OPEN_IN_ANOTHER_APP_ON_SHEET]. */
        OpenInAnotherApp,

        Back,
    }

    /**
     * The hook for PR4: flip this once "Open in another app" is meant to sit on the sheet, and it
     * appears (where the chat allows saving) between Telegram's lighter copy and Back, with the
     * button's visibility and its press wired in `showSlowStart`. Off today, as the plan asks.
     */
    const val OPEN_IN_ANOTHER_APP_ON_SHEET = false

    /**
     * The buttons, top to bottom. [reason] is why nothing arrives: with no line to Telegram a lighter
     * copy cannot help, so it is only offered for [OpeningStall.Reason.Slow].
     */
    fun actions(
        reason: OpeningStall.Reason,
        canDownload: Boolean,
        lighterCopy: Boolean,
        canOpenInAnotherApp: Boolean,
    ): List<Action> = buildList {
        add(Action.TryAgain)
        if (canDownload) add(Action.DownloadFirst)
        if (lighterCopy && reason == OpeningStall.Reason.Slow) add(Action.LighterCopy)
        if (OPEN_IN_ANOTHER_APP_ON_SHEET && canOpenInAnotherApp) add(Action.OpenInAnotherApp)
        add(Action.Back)
    }
}
