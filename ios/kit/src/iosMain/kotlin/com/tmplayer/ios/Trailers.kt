package com.tmplayer.ios

import com.tmplayer.ui.trailer.TrailerHost
import com.tmplayer.ui.trailer.TrailerHostListener
import com.tmplayer.ui.trailer.TrailerHostPresenter
import com.tmplayer.ui.trailer.TrailerShown
import com.tmplayer.ui.trailer.TrailerTexts

/**
 * The words one trailer sheet shows, in the viewer's language (TMPlayer's catalogs), and the
 * script the sheet runs in the page to pause it. Mirrors :ios's TrailerTexts, which Swift cannot
 * see (this module does not export :ios).
 */
class TrailerSheetTexts(
    val title: String,
    val loading: String,
    val close: String,
    val watchOnYoutube: String,
    val unavailable: String,
    val pauseScript: String,
)

/** What the sheet tells Kotlin, on the main thread. */
interface TrailerSheetListener {
    /** One message from the page, as the page posted it; Kotlin reads it as outside data. */
    fun onMessage(raw: String)

    /** The viewer chose Watch on YouTube, or the player linked to YouTube: Kotlin opens it outside. */
    fun onLeave()

    /** The sheet is gone, by Close, a swipe down or [TrailerSheetHandle.close]. */
    fun onClosed()
}

/** The sheet showing, which Kotlin drives. */
interface TrailerSheetHandle {
    /** Shows the "cannot play here" text over the picture. */
    fun showNotice()

    /** Dismisses the sheet and tears its web view down; safe to call twice. */
    fun close()
}

/**
 * Swift's trailer sheet (iosApp/TMPlayer/Player/TrailerSheet.swift): presents a WKWebView that
 * loads [html] (TrailerEmbed.pageHtml, base URL youtube-nocookie.com) and answers the handle, or
 * null when it cannot present (nothing on screen to present from).
 */
interface TrailerSheetPresenter {
    fun present(html: String, texts: TrailerSheetTexts, listener: TrailerSheetListener): TrailerSheetHandle?
}

/** The trailer player's seam to Swift, as [Engines] is the video engine's. */
object Trailers {
    /** Called by the app as it starts (iosApp, TMPlayerApp.swift), with the sheet Swift presents. */
    fun install(presenter: TrailerSheetPresenter) {
        TrailerHost.presenter = TrailerHostPresenter { html, texts, listener ->
            val handle = presenter.present(
                html,
                TrailerSheetTexts(texts.title, texts.loading, texts.close, texts.watchOnYoutube, texts.unavailable, texts.pauseScript),
                object : TrailerSheetListener {
                    override fun onMessage(raw: String) = listener.onMessage(raw)
                    override fun onLeave() = listener.onLeave()
                    override fun onClosed() = listener.onClosed()
                },
            )
            handle?.let { shown ->
                object : TrailerShown {
                    override fun showNotice() = shown.showNotice()
                    override fun close() = shown.close()
                }
            }
        }
    }
}
