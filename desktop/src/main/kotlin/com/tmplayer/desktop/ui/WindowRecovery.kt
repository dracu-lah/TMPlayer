package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowState
import com.tmplayer.desktop.DesktopCrashReports
import com.tmplayer.ui.components.EmptyArt
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import javax.swing.SwingUtilities

/**
 * What happens when a screen throws while it is composed, measured, drawn or used (HX10).
 *
 * Compose Desktop's own answer was a bare "Error" dialog and the window closed, which quit the app;
 * and a composition that has thrown is dead for good (its recomposer stops, and every frame after
 * fails with "layout state is not idle before measure starts"), so nothing inside it can be
 * caught and carried on from. Here the window's exception handler logs the failure, hands it to
 * the crash reporting ([DesktopCrashReports.contained]: sent with the switch on, asked about at
 * once with it off) and builds the window again ([RecoveringWindow], keyed on [generation]) with
 * the error card ([ShellOrFailure]) where the shell was: Try again, Settings, Save logs and Report
 * on GitHub. Nothing restarts by itself: the shell comes back only when the viewer asks.
 *
 * It cannot loop. A failure in the card's own window, or more than [MAX_FAILURES] in
 * [WINDOW_MS], is past helping, and goes to Compose's own handler as before (its dialog, then the
 * window closes and the app quits), as does running out of memory. Late errors from a window
 * already being replaced belong to the failure being handled and are only logged.
 *
 * One error is not a failure at all: Compose's own race between a popup closing and the window
 * laying it out ([isClosedLayerRace]) loses one event and leaves the window well, so it is logged
 * and let pass, up to [MAX_PASSING] a minute.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WindowRecovery(
    private val now: () -> Long = System::currentTimeMillis,
    private val later: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val report: (Throwable) -> Unit = DesktopCrashReports::contained,
    private val giveUp: (java.awt.Window?, Throwable) -> Unit = { window, error ->
        if (window != null) DefaultWindowExceptionHandlerFactory.exceptionHandler(window).onException(error) else throw error
    },
) {
    /** Bumped to build the window again around a fresh composition. */
    var generation by mutableIntStateOf(0)
        private set

    /** The failure the card is showing, or null while the shell is. */
    var failure by mutableStateOf<Throwable?>(null)
        private set

    private val recent = ArrayDeque<Long>()

    /** What [RecoveringWindow] hands to the window: one handler per window, tied to its generation. */
    val factory: WindowExceptionHandlerFactory = object : WindowExceptionHandlerFactory {
        override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
            val built = generation
            return object : WindowExceptionHandler {
                override fun onException(throwable: Throwable) = contain(built, window, throwable)
            }
        }
    }

    /** The handler's work, for [factory] and the tests. Runs on whatever thread the error is on. */
    @Synchronized
    fun contain(built: Int, window: java.awt.Window?, error: Throwable) {
        val at = now()
        if (isClosedLayerRace(error)) {
            passing.addLast(at)
            while (passing.isNotEmpty() && at - passing.first() > WINDOW_MS) passing.removeFirst()
            if (passing.size <= MAX_PASSING) {
                runCatching {
                    System.err.println(
                        "W/Crash: a popup closed while Compose was laying it out, on ${Thread.currentThread().name}: " +
                            "that event is dropped and the window carries on",
                    )
                    error.printStackTrace()
                    System.err.flush()
                }
                return
            }
            // Over and over: the scene may hold a closed layer for good, so the card is the way out.
        }
        runCatching {
            System.err.println("E/Crash: contained on ${Thread.currentThread().name}, the window shows its error card")
            error.printStackTrace()
            System.err.flush()
        }
        // An older window's late errors (each frame fails once a composition is dead) belong to
        // the failure already being handled.
        if (built != generation || swapping) return
        recent.addLast(at)
        while (recent.isNotEmpty() && at - recent.first() > WINDOW_MS) recent.removeFirst()
        if (error is VirtualMachineError || failure != null || recent.size > MAX_FAILURES) {
            giveUp(window, error)
            return
        }
        swapping = true
        runCatching { report(error) }
        later {
            synchronized(this) {
                failure = error
                generation += 1
                swapping = false
            }
        }
    }

    /** The card's Try again: the shell is drawn again in the card's (healthy) window. */
    fun tryAgain() {
        failure = null
    }

    @Volatile
    private var swapping = false

    /** When each [isClosedLayerRace] was let pass, for [MAX_PASSING]. */
    private val passing = ArrayDeque<Long>()

    companion object {
        /** How many contained failures in [WINDOW_MS] before the window gives up. */
        const val MAX_FAILURES = 4
        const val WINDOW_MS = 60_000L

        /** How many [isClosedLayerRace] in [WINDOW_MS] are let pass before the card is shown. */
        const val MAX_PASSING = 20

        /**
         * Compose Multiplatform 1.12's race between a popup closing and the window laying out its
         * layers, which is harmless and so is not a failure of the screen.
         *
         * The window's scene (`CanvasLayersComposeSceneImpl.doMeasureAndLayout`, before each
         * pointer event and each frame) copies its list of owners (the window's own content, then
         * one per open popup, menu or tooltip) and measures them in turn. Measuring the content
         * also composes what is composed at measure time (BoxWithConstraints, the items of lazy
         * lists and grids), and that can drop an open popup when its room changes; the popup's
         * layer is then closed, taken off the live list and disposed, but it is still in the copy,
         * and measuring it next fails with `IllegalArgumentException: RootNodeOwner is already
         * disposed` (WindowRecoveryTest draws it). The content was measured, the closed popup is
         * gone from the next pass, and nothing else is left half done: only that event (a mouse
         * release in a macOS log of 1.28.2-beta.2) or frame is lost. Before this, the window threw
         * its shell away for the error card.
         *
         * Matched narrowly: that message, thrown by `RootNodeOwner` itself, with the layered scene
         * on the stack.
         */
        fun isClosedLayerRace(error: Throwable): Boolean {
            if (error !is IllegalArgumentException || error.message != "RootNodeOwner is already disposed") return false
            val frames = error.stackTrace
            if (frames.firstOrNull()?.className != "androidx.compose.ui.node.RootNodeOwner") return false
            return frames.any { it.className.startsWith("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl") }
        }
    }
}

/**
 * Compose's [Window] with [recovery]'s exception handler, built again whenever the recovery asks
 * (a failed composition cannot be revived). Takes the parameters the app's window uses.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecoveringWindow(
    recovery: WindowRecovery,
    onCloseRequest: () -> Unit,
    state: WindowState,
    title: String,
    undecorated: Boolean,
    icon: Painter?,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    key(recovery.generation) {
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides recovery.factory) {
            Window(
                onCloseRequest = onCloseRequest,
                state = state,
                title = title,
                undecorated = undecorated,
                icon = icon,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
        }
    }
}

/**
 * The shell, or while [recovery] holds a failure, the card in its place. [onTryAgain] puts the
 * shell back as the viewer left it, less the player; [onSettings] opens Settings instead, the way
 * out when the same screen fails each time (a newer version may be waiting there).
 */
@Composable
fun ShellOrFailure(
    recovery: WindowRecovery,
    onTryAgain: () -> Unit,
    onSettings: () -> Unit,
    shell: @Composable () -> Unit,
) {
    if (recovery.failure == null) {
        shell()
        return
    }
    WindowFailureCard(
        onTryAgain = {
            onTryAgain()
            recovery.tryAgain()
        },
        onSettings = {
            onSettings()
            recovery.tryAgain()
        },
    )
}

/** The card itself: what happened in a line, Try again (focused), Settings, and the logs. */
@Composable
fun WindowFailureCard(onTryAgain: () -> Unit, onSettings: () -> Unit) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    Centred {
        EmptyArt(TmIcons.Warning, tone = Tone.danger, modifier = Modifier.padding(bottom = 4.dp))
        Text(s.errorsScreenFailedTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(s.errorsScreenFailed, color = Tone.muted, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTryAgain, modifier = Modifier.focusRequester(first)) { Text(s.commonTryAgain) }
            OutlinedButton(onClick = onSettings) { Text(s.navSettings) }
        }
        SendLogsActions()
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
