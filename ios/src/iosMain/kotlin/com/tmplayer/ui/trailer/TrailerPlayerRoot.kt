package com.tmplayer.ui.trailer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.MobileData
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerPageLabels
import com.tmplayer.online.TrailerRules
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.LocalTrailerOpener
import com.tmplayer.ui.online.TrailerOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// iOS's trailer player (private/TRAILERS-PLAN.md, T5): YouTube's embedded player in a WKWebView in a
// sheet the Swift shell shows (iosApp/TMPlayer/Player/TrailerSheet.swift). This file is the Kotlin half: the
// TrailerOpener the screens call, and the state machine, through the same TrailerSession and
// core's TrailerRules as Android's. Swift only loads the page, reports its messages and the
// viewer's choices, and shows what it is told. Android's own player (TrailerPlayer.kt,
// TrailerWebPage.kt) is not compiled for iOS (androidScreensForIos in ios/build.gradle.kts).

/** The words the sheet shows and the script it pauses the page with, in the viewer's language. */
class TrailerTexts(
    val title: String,
    val loading: String,
    val close: String,
    val watchOnYoutube: String,
    val unavailable: String,
    val pauseScript: String,
)

/** What the sheet tells Kotlin. Called on the main thread. */
interface TrailerHostListener {
    /** One message from the page, read as outside data by [TrailerEmbed.parseMessage]. */
    fun onMessage(raw: String)

    /** The viewer chose Watch on YouTube, or YouTube's player linked out: the trailer opens outside. */
    fun onLeave()

    /** The sheet is gone (Close, a swipe down, or Kotlin's own close): nothing more to do. */
    fun onClosed()
}

/** A sheet showing. */
interface TrailerShown {
    /** Shows [TrailerTexts.unavailable] over the picture. */
    fun showNotice()

    /** Dismisses the sheet and tears the web view down. Safe to call twice. */
    fun close()
}

/** The Swift shell's side: shows a sheet that loads [html], or answers null when it cannot. */
fun interface TrailerHostPresenter {
    fun present(html: String, texts: TrailerTexts, listener: TrailerHostListener): TrailerShown?
}

/** Where the Swift shell plugs its presenter in as the app starts (TMPlayerKit's `Trailers.install`). */
object TrailerHost {
    var presenter: TrailerHostPresenter? = null
}

/**
 * iOS's [TrailerOpener]. The launcher only calls [open] with the viewer's `trailers_in_app`
 * setting on, a valid key and [online]; with no presenter, or one that cannot show the sheet, it
 * goes straight to `onFallback` (the YouTube app or Safari, as before). Nothing from Google loads
 * before [open], which only the trailer button's press calls.
 *
 * An error from YouTube that [TrailerRules] sends outside, or no `ready` within
 * [readyTimeoutMs], shows the notice for [noticeMs] and then closes the sheet and calls
 * `onFallback` once. A second [open] replaces the first without a fallback for it.
 */
class IosTrailerOpener(
    private val network: () -> Boolean,
    private val texts: () -> TrailerTexts,
    private val scope: CoroutineScope,
    private val presenter: () -> TrailerHostPresenter? = { TrailerHost.presenter },
    private val readyTimeoutMs: Long = TrailerRules.READY_TIMEOUT_MS,
    private val noticeMs: Long = NOTICE_MS,
) : TrailerOpener {

    private var current: Active? = null

    override val online: Boolean get() = network()

    override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
        val host = presenter()
        val words = texts()
        val html = TrailerEmbed.pageHtml(trailer.key, TrailerPageLabels(words.title, words.loading))
        if (host == null || html == null) {
            onFallback()
            return
        }
        current?.end(close = true)
        val active = Active(onFallback)
        current = active
        val shown = runCatching { host.present(html, words, active) }.getOrNull()
        if (shown == null) {
            active.end(close = false)
            onFallback()
            return
        }
        active.shown = shown
        // Already done if the sheet was closed while it was being presented.
        if (!active.done) {
            active.timer = scope.launch {
                delay(readyTimeoutMs)
                active.session.onTimeout()
                active.checkFallback()
            }
        }
    }

    /** Closes the sheet showing, if any, with no fallback (the app's root went away). */
    fun dismiss() {
        current?.end(close = true)
    }

    private inner class Active(private val onFallback: () -> Unit) : TrailerHostListener {
        val session = TrailerSession()
        var shown: TrailerShown? = null
        var timer: Job? = null
        private var notice: Job? = null
        var done = false
            private set

        override fun onMessage(raw: String) {
            if (done) return
            session.onMessage(raw)
            checkFallback()
        }

        fun checkFallback() {
            if (done || notice != null || !session.state.fallsBack) return
            notice = scope.launch {
                shown?.showNotice()
                delay(noticeMs)
                leave()
            }
        }

        override fun onLeave() = leave()

        override fun onClosed() = end(close = false)

        private fun leave() {
            if (done) return
            end(close = true)
            onFallback()
        }

        fun end(close: Boolean) {
            if (done) return
            done = true
            timer?.cancel()
            notice?.cancel()
            if (current === this) current = null
            if (close) runCatching { shown?.close() }
        }
    }

    private companion object {
        /** How long "cannot play here" shows before the trailer opens outside, as on Android. */
        const val NOTICE_MS = 1_500L
    }
}

/**
 * Provides iOS's trailer player to everything in [content]; put once at the app's root, where
 * Android's root has its own. The "On mobile data" answer is read as it changes, so the check at
 * the tap is current ([trailerMayStream]).
 */
@Composable
fun TrailerPlayerRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by rememberUpdatedState(LocalStrings.current)
    val answers = remember(context) { SettingsStore(context).mobileData.catch { emit(MobileData.Ask) } }
    val mobileData by answers.collectAsStateWithLifecycle(initialValue = MobileData.Ask)
    val answer by rememberUpdatedState(mobileData)
    val opener = remember(context, scope) {
        IosTrailerOpener(
            network = {
                trailerMayStream(
                    offline = NetworkMonitor.status.value == NetworkStatus.Offline,
                    metered = NetworkMonitor.metered.value,
                    mobileData = answer,
                    television = false,
                )
            },
            texts = {
                TrailerTexts(
                    title = s.trailerTitle,
                    loading = s.trailerLoading,
                    close = s.trailerClose,
                    watchOnYoutube = s.metadataTrailerOnYoutube,
                    unavailable = s.trailerUnavailableInApp,
                    pauseScript = TrailerEmbed.PAUSE,
                )
            },
            scope = scope,
        )
    }
    DisposableEffect(opener) { onDispose { opener.dismiss() } }
    CompositionLocalProvider(LocalTrailerOpener provides opener) { content() }
}
