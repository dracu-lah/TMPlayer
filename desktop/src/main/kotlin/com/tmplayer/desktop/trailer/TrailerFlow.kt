package com.tmplayer.desktop.trailer

import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerPhase
import com.tmplayer.online.TrailerRules
import com.tmplayer.online.TrailerState
import java.io.File

/** What the desktop knows of its trailer player when a trailer is pressed. */
sealed interface EngineStatus {
    /** No Chromium for this OS and processor ([CefNatives.forSystem]). */
    data object Unsupported : EngineStatus

    /** Chromium was installed but would not start in this run; it cannot be started twice in one process. */
    data object StartFailed : EngineStatus

    data class Installed(val dir: File) : EngineStatus

    /** Not downloaded yet: the consent dialog shows [offer]. */
    data class Missing(val offer: EngineOffer) : EngineStatus
}

/** What is on screen for a trailer. */
sealed interface TrailerScreen {
    val trailer: MetaTrailer?

    data object None : TrailerScreen {
        override val trailer: MetaTrailer? get() = null
    }

    /** The consent dialog: what the player is, its size and the free space. */
    data class Asking(override val trailer: MetaTrailer, val offer: EngineOffer) : TrailerScreen

    /** Downloading or unpacking, with Cancel. [progress] is null until the first bytes. */
    data class Installing(override val trailer: MetaTrailer, val progress: EngineProgress? = null) : TrailerScreen

    /** The sheet, from Chromium starting to the trailer's end. [state] is [TrailerRules]'. */
    data class Playing(override val trailer: MetaTrailer, val dir: File, val state: TrailerState = TrailerState()) : TrailerScreen
}

/** Why a trailer went to the external open. Each one but [Declined] says so in a line. */
enum class TrailerFallback { Unsupported, Declined, Cancelled, DownloadFailed, StartFailed, Page }

/** What the host does after a step. */
sealed interface TrailerEffect {
    data object None : TrailerEffect
    data class Install(val offer: EngineOffer.Fits) : TrailerEffect

    /** Start Chromium (once per run) and load the page in a new browser. */
    data class Start(val dir: File) : TrailerEffect

    /** Close what is open and open [trailer] outside, saying why unless the viewer chose it. */
    data class Fallback(val trailer: MetaTrailer, val reason: TrailerFallback) : TrailerEffect
}

/** The keys the sheet answers, from Compose's focus or from inside the browser. */
enum class TrailerKey { Toggle, Back, Forward, Close }

data class TrailerStep(val screen: TrailerScreen, val effect: TrailerEffect = TrailerEffect.None)

/**
 * The desktop's in-app trailer, step by step (private/TRAILERS-PLAN.md, T4). Pure: [DesktopTrailers]
 * feeds it what happened and does the [TrailerEffect] it answers. The page's messages and the ready
 * timeout go through [TrailerRules], so the desktop falls back exactly where the other platforms do.
 *
 * Anything that arrives for a screen that is no longer up (a progress after Cancel, a message from
 * a page that was closed) changes nothing.
 */
object TrailerFlow {

    /** How far Left and Right move the trailer. */
    const val SEEK_SECONDS = 10

    /** The trailer button, with what is on disk. A press while something is already up is ignored. */
    fun tap(screen: TrailerScreen, trailer: MetaTrailer, status: EngineStatus): TrailerStep {
        if (screen != TrailerScreen.None) return TrailerStep(screen)
        return when (status) {
            EngineStatus.Unsupported -> TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(trailer, TrailerFallback.Unsupported))
            EngineStatus.StartFailed -> TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(trailer, TrailerFallback.StartFailed))
            is EngineStatus.Installed -> TrailerStep(TrailerScreen.Playing(trailer, status.dir), TrailerEffect.Start(status.dir))
            is EngineStatus.Missing -> TrailerStep(TrailerScreen.Asking(trailer, status.offer))
        }
    }

    /** Download, only from the dialog and only where it fits. */
    fun accept(screen: TrailerScreen): TrailerStep {
        val asking = screen as? TrailerScreen.Asking ?: return TrailerStep(screen)
        val offer = asking.offer as? EngineOffer.Fits ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.Installing(asking.trailer), TrailerEffect.Install(offer))
    }

    /** Watch on YouTube, from the dialog. */
    fun decline(screen: TrailerScreen): TrailerStep {
        val asking = screen as? TrailerScreen.Asking ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(asking.trailer, TrailerFallback.Declined))
    }

    /** Esc or a click outside the dialog: nothing downloads and nothing opens. */
    fun dismiss(screen: TrailerScreen): TrailerStep =
        if (screen is TrailerScreen.Asking) TrailerStep(TrailerScreen.None) else TrailerStep(screen)

    fun progress(screen: TrailerScreen, progress: EngineProgress): TrailerStep =
        if (screen is TrailerScreen.Installing) TrailerStep(screen.copy(progress = progress)) else TrailerStep(screen)

    /**
     * Cancel during the download: the dialog goes at once and the trailer opens outside. The host
     * stops the install job whenever the screen leaves [TrailerScreen.Installing], and the job
     * removes what it wrote.
     */
    fun cancel(screen: TrailerScreen): TrailerStep {
        val installing = screen as? TrailerScreen.Installing ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(installing.trailer, TrailerFallback.Cancelled))
    }

    fun installed(screen: TrailerScreen, dir: File): TrailerStep {
        val installing = screen as? TrailerScreen.Installing ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.Playing(installing.trailer, dir), TrailerEffect.Start(dir))
    }

    fun installFailed(screen: TrailerScreen): TrailerStep {
        val installing = screen as? TrailerScreen.Installing ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(installing.trailer, TrailerFallback.DownloadFailed))
    }

    /** Chromium would not start. */
    fun startFailed(screen: TrailerScreen): TrailerStep {
        val playing = screen as? TrailerScreen.Playing ?: return TrailerStep(screen)
        return TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(playing.trailer, TrailerFallback.StartFailed))
    }

    /** A message from the page ([TrailerEmbed.parseMessage] reads it; anything else is ignored). */
    fun message(screen: TrailerScreen, raw: String?): TrailerStep {
        val playing = screen as? TrailerScreen.Playing ?: return TrailerStep(screen)
        return after(playing, TrailerRules.next(playing.state, TrailerEmbed.parseMessage(raw)))
    }

    /** The page itself could not be loaded: what the page says when YouTube's script does not arrive. */
    fun loadFailed(screen: TrailerScreen): TrailerStep = message(screen, "error:-1")

    /** [TrailerRules.READY_TIMEOUT_MS] after the page was loaded. */
    fun timedOut(screen: TrailerScreen): TrailerStep {
        val playing = screen as? TrailerScreen.Playing ?: return TrailerStep(screen)
        return after(playing, TrailerRules.timedOut(playing.state))
    }

    /** Esc, the Close button, or a click outside the sheet. */
    fun close(screen: TrailerScreen): TrailerStep =
        if (screen is TrailerScreen.Playing) TrailerStep(TrailerScreen.None) else TrailerStep(screen)

    /** The script a key runs in the page, or null for Close (which is [close]). Nothing until the player is ready. */
    fun script(screen: TrailerScreen, key: TrailerKey): String? {
        val playing = screen as? TrailerScreen.Playing ?: return null
        if (!playing.state.controllable) return null
        return when (key) {
            TrailerKey.Toggle -> TrailerEmbed.TOGGLE
            TrailerKey.Back -> TrailerEmbed.seekBy(-SEEK_SECONDS)
            TrailerKey.Forward -> TrailerEmbed.seekBy(SEEK_SECONDS)
            TrailerKey.Close -> null
        }
    }

    private fun after(playing: TrailerScreen.Playing, next: TrailerState): TrailerStep = when {
        next.fallsBack -> TrailerStep(TrailerScreen.None, TrailerEffect.Fallback(playing.trailer, TrailerFallback.Page))
        next == playing.state -> TrailerStep(playing)
        else -> TrailerStep(playing.copy(state = next))
    }

    /** Whether the page is still waiting for YouTube's `ready`, so the timeout still counts. */
    fun waiting(screen: TrailerScreen): Boolean = screen is TrailerScreen.Playing && screen.state.phase == TrailerPhase.Loading
}
