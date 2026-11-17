package com.tmplayer.desktop.trailer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerPageLabels
import com.tmplayer.platform.Logger
import com.tmplayer.ui.online.TrailerOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A trailer page in a browser. [component] is the AWT view to show, null for a browser with nothing to draw (tests). */
interface TrailerBrowser {
    val component: java.awt.Component?

    /** Runs one of [TrailerEmbed]'s scripts in the page. */
    fun run(script: String)

    /** Closes the page and frees the browser. Called once, when the sheet goes. */
    fun dispose()

    /** What the page and the keys inside it say. Called on any thread. */
    interface Listener {
        fun onMessage(raw: String?)
        fun onKey(key: TrailerKey)
        fun onLoadFailed()
    }
}

/** The desktop's trailer player: where it stands on disk, installing it, and its browsers. The fake in tests. */
interface TrailerEngine {
    /** Reads the disk; called off the main thread. */
    fun status(): EngineStatus

    /** See [EngineInstaller.install]. */
    suspend fun install(offer: EngineOffer.Fits, onProgress: (EngineProgress) -> Unit): File

    /** Starts Chromium from [dir], once per run. False when it will not. */
    suspend fun start(dir: File): Boolean

    /** A new browser showing [html] at [TrailerEmbed.BASE_ORIGIN]. Only after [start] answered true. */
    fun browser(html: String, listener: TrailerBrowser.Listener): TrailerBrowser
}

/**
 * The desktop's [TrailerOpener] (private/TRAILERS-PLAN.md, T4), provided at the window's root. It
 * holds what [DesktopTrailerHost] shows and does what [TrailerFlow] answers.
 *
 * Nothing is read, downloaded or started before a trailer is pressed: the first press looks at the
 * disk, and a missing player is downloaded only from the consent dialog's Download button.
 * [TrailerOpener.online] is false while the computer has no network, so the launcher goes straight
 * to YouTube.
 */
@Stable
class DesktopTrailers(
    private val engine: TrailerEngine,
    private val scope: CoroutineScope,
    /** A line for the viewer (the window's notice). */
    private val notify: (String) -> Unit,
    private val isOnline: () -> Boolean = { true },
) : TrailerOpener {

    var screen: TrailerScreen by mutableStateOf(TrailerScreen.None)
        private set

    /** The page's browser, once Chromium has started for the trailer on screen. */
    var browser: TrailerBrowser? by mutableStateOf(null)
        private set

    override val online: Boolean get() = isOnline()

    private var fallback: (() -> Unit)? = null
    private var installJob: Job? = null
    private var looking = false

    /** Each open's browser carries its number, so a late message from a closed page is dropped. */
    private var session = 0

    override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
        if (screen != TrailerScreen.None || looking) return
        looking = true
        scope.launch {
            val status = try {
                withContext(Dispatchers.IO) { engine.status() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not look for the trailer player: ${e.message}")
                EngineStatus.Unsupported
            } finally {
                looking = false
            }
            fallback = onFallback
            apply(TrailerFlow.tap(screen, trailer, status))
        }
    }

    fun accept() = apply(TrailerFlow.accept(screen))
    fun decline() = apply(TrailerFlow.decline(screen))
    fun dismiss() = apply(TrailerFlow.dismiss(screen))
    fun cancel() = apply(TrailerFlow.cancel(screen))
    fun close() = apply(TrailerFlow.close(screen))
    fun timedOut() = apply(TrailerFlow.timedOut(screen))

    /** A key from the sheet or from inside the browser. */
    fun key(key: TrailerKey) {
        if (key == TrailerKey.Close) return close()
        val script = TrailerFlow.script(screen, key) ?: return
        runCatching { browser?.run(script) }.onFailure { Logger.w(TAG, "script failed: ${it.message}") }
    }

    /** The window went to the background (minimised): the trailer stops there. */
    fun pause() {
        if (screen is TrailerScreen.Playing) runCatching { browser?.run(TrailerEmbed.PAUSE) }
    }

    private fun apply(step: TrailerStep) {
        val before = screen
        screen = step.screen
        if (before is TrailerScreen.Installing && step.screen !is TrailerScreen.Installing) {
            installJob?.cancel()
            installJob = null
        }
        if (before is TrailerScreen.Playing && step.screen !is TrailerScreen.Playing) closeBrowser()
        when (val effect = step.effect) {
            TrailerEffect.None -> Unit
            is TrailerEffect.Install -> install(effect.offer)
            is TrailerEffect.Start -> start(effect.dir)
            is TrailerEffect.Fallback -> fallBack(effect.reason)
        }
    }

    private fun install(offer: EngineOffer.Fits) {
        installJob?.cancel()
        var shown = -1
        installJob = scope.launch {
            try {
                val dir = engine.install(offer) { progress ->
                    // A step a percent is plenty for a bar, and keeps a fast disk from flooding the window.
                    val (done, total) = when (progress) {
                        is EngineProgress.Downloading -> progress.done to progress.total
                        is EngineProgress.Unpacking -> progress.done to progress.total
                    }
                    val percent = (if (total > 0) done * 100 / total else 0L).toInt() + if (progress is EngineProgress.Unpacking) 1000 else 0
                    if (percent != shown) {
                        shown = percent
                        scope.launch { apply(TrailerFlow.progress(screen, progress)) }
                    }
                }
                apply(TrailerFlow.installed(screen, dir))
            } catch (e: CancellationException) {
                // Cancel already moved the screen on; nothing more to say.
            } catch (e: Exception) {
                Logger.w(TAG, "trailer player install failed: ${e.message}")
                apply(TrailerFlow.installFailed(screen))
            }
        }
    }

    private fun start(dir: File) {
        val mine = ++session
        scope.launch {
            val ok = try {
                engine.start(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "trailer player did not start: ${e.message}")
                false
            }
            if (mine != session || screen !is TrailerScreen.Playing) return@launch
            if (!ok) return@launch apply(TrailerFlow.startFailed(screen))
            val trailer = screen.trailer ?: return@launch
            val html = TrailerEmbed.pageHtml(trailer.key, TrailerPageLabels(L.trailerTitle, L.trailerLoading))
                ?: return@launch apply(TrailerFlow.message(screen, "error:2"))
            browser = try {
                engine.browser(html, listenerFor(mine))
            } catch (e: Throwable) {
                Logger.w(TAG, "trailer browser failed: ${e.message}")
                null
            }
            if (browser == null) apply(TrailerFlow.startFailed(screen))
        }
    }

    private fun listenerFor(mine: Int) = object : TrailerBrowser.Listener {
        override fun onMessage(raw: String?) = onMain(mine) { apply(TrailerFlow.message(screen, raw)) }
        override fun onKey(key: TrailerKey) = onMain(mine) { this@DesktopTrailers.key(key) }
        override fun onLoadFailed() = onMain(mine) { apply(TrailerFlow.loadFailed(screen)) }
    }

    private fun onMain(mine: Int, block: () -> Unit) {
        scope.launch { if (mine == session) block() }
    }

    private fun closeBrowser() {
        session++
        val open = browser ?: return
        browser = null
        runCatching { open.dispose() }.onFailure { Logger.w(TAG, "browser dispose failed: ${it.message}") }
    }

    private fun fallBack(reason: TrailerFallback) {
        val open = fallback
        fallback = null
        message(reason)?.let(notify)
        open?.invoke()
    }

    companion object {
        private const val TAG = "DesktopTrailers"

        /** What the window says as the trailer goes outside, or null when the viewer chose it. */
        fun message(reason: TrailerFallback): String? = when (reason) {
            TrailerFallback.Declined -> null
            TrailerFallback.Unsupported -> L.trailerEngineUnsupported
            TrailerFallback.Cancelled -> L.trailerEngineCancelled
            TrailerFallback.DownloadFailed -> L.trailerEngineFailed
            TrailerFallback.StartFailed -> L.trailerEngineStartFailed
            TrailerFallback.Page -> L.trailerUnavailableInApp
        }
    }
}
