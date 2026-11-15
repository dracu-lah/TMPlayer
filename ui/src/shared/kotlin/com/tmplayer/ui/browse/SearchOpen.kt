package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.PlayPress

/**
 * A tap on a search result, answered at once.
 *
 * Learning that the video is still there ([GlobalSearchViewModel.check]) takes up to
 * [VideoSearchSource.CHECK_MS] on a slow line, and a tap that shows nothing for that long reads as
 * missed. So the press is a [PlayPress] meanwhile: the shell's "Starting" pill shows after a beat,
 * and a second tap on any result while it is up is dropped, so one tap opens one page. It ends
 * before [onOpen] runs, which a Play of its own then takes over with a press of its own.
 *
 * [onGone] says the video is no longer there (its card is already out of the list); [onOpen]
 * opens the page or plays.
 */
suspend fun GlobalSearchViewModel.openResult(lead: MediaItem, title: String, onGone: () -> Unit, onOpen: () -> Unit) {
    if (PlayPress.starting) return
    val press = PlayPress.begin(title.ifBlank { lead.title })
    val there = try {
        check(lead)
    } finally {
        PlayPress.end(press)
    }
    if (there) onOpen() else onGone()
}
