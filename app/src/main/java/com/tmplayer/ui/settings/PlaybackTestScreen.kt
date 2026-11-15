package com.tmplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.i18n.Strings
import com.tmplayer.player.AndroidPlaybackTestEnv
import com.tmplayer.player.PlaybackTest
import com.tmplayer.player.PlaybackTest.Verdict
import com.tmplayer.player.PlaybackTestRunner
import com.tmplayer.player.PlaybackTestSession
import com.tmplayer.player.PlaybackTestSession.State
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.PanelActions
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface

/**
 * Settings, Help, "Playback test" (reliability plan L6): plays the first 30 seconds of a video
 * from the disk, muted, with the network out of the picture, and says whether the device's decoder
 * or the line is behind a stutter. The returned action opens the panel; the steps are
 * [PlaybackTestSession]'s.
 *
 * A panel in a window of its own, like Send to developer's, so the remote reaches it the same way:
 * the first button of each step is focused, Cancel stops a run and leaves nothing on the disk, and
 * closing returns the focus to the row that opened it.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlaybackTest(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { PlaybackTestRunner(context.applicationContext) }
    val session = remember { PlaybackTestSession(AndroidPlaybackTestEnv(context.applicationContext, runner = runner)) }
    // Leaving the screen stops a run and deletes what a fetch brought.
    DisposableEffect(session) {
        onDispose {
            session.close()
            runner.stop()
        }
    }
    val state by session.state.collectAsStateWithLifecycle()
    if (state !is State.Idle) {
        TestPanel(
            state = state,
            runner = runner,
            onStart = { session.start(scope) },
            onCancel = { session.cancel() },
            onClose = { session.close() },
            onAdd = { session.addToReport(scope) },
            onAgain = {
                session.close()
                session.begin(scope)
            },
        )
    }
    return { session.begin(scope) }
}

@OptIn(UnstableApi::class)
@Composable
private fun TestPanel(
    state: State,
    runner: PlaybackTestRunner,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onAgain: () -> Unit,
) {
    val s = LocalStrings.current
    val primary = remember(state::class) { FocusRequester() }
    val running = state is State.Fetching || state is State.Testing
    // Back on a run stops it; anywhere else it closes.
    FloatingWindow(onDismiss = { if (running) onCancel() else onClose() }, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, 680.dp)
        Column(
            Modifier.width(panel).floatingSurface().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.playbacktestTitle, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            when (state) {
                State.Idle -> Unit
                State.Looking -> {
                    Body(s.playbacktestLooking)
                    PanelActions { TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) } }
                }
                State.Refused -> {
                    Body(s.playbacktestRefused)
                    PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) } }
                }
                State.NoVideo -> {
                    Body(s.playbacktestNoVideo)
                    PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) } }
                }
                is State.Ready -> {
                    Body(s.playbacktestVideo(state.video.title))
                    val fetch = state.fetch
                    Body(
                        if (fetch == null) {
                            s.playbacktestReady
                        } else {
                            s.playbacktestFetchOffer(s.formatter.bytes(fetch.bytes), s.formatter.bytes(fetch.freeBytes))
                        },
                    )
                    PanelActions {
                        TmSecondaryButton(onClick = onClose) { Text(s.commonCancel) }
                        TmButton(onClick = onStart, modifier = Modifier.focusRequester(primary)) {
                            Text(if (fetch == null) s.playbacktestStart else s.playbacktestFetchStart)
                        }
                    }
                }
                is State.NoSpace -> {
                    Body(s.playbacktestVideo(state.video.title))
                    Body(s.playbacktestNoSpace(s.formatter.bytes(state.needed), s.formatter.bytes(state.free)))
                    PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) } }
                }
                is State.Fetching -> {
                    Body(s.playbacktestFetching(s.formatter.bytes(state.bytes), s.formatter.bytes(state.total)))
                    PanelActions { TmSecondaryButton(onClick = onCancel, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) } }
                }
                is State.Testing -> {
                    Preview(runner)
                    Body(s.playbacktestTesting((state.playedMs / 1000).toInt(), (PlaybackTest.WINDOW_MS / 1000).toInt()))
                    PanelActions { TmSecondaryButton(onClick = onCancel, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) } }
                }
                is State.Done -> {
                    val r = state.result
                    Text(verdictWords(s, r.verdict), style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                    if (r.verdict != Verdict.COULD_NOT_PLAY && r.verdict != Verdict.TOO_SHORT) {
                        Body(s.playbacktestDropped(r.droppedFrames))
                        if (r.decoder.isNotBlank()) {
                            Body(s.playbacktestDecoder(r.decoder, if (r.hardware) s.playbacktestHardware else s.playbacktestSoftware))
                        }
                    }
                    if (state.added) Body(s.playbacktestAdded)
                    if (state.addFailed) Body(s.playbacktestAddFailed)
                    PanelActions {
                        if (!state.added) {
                            TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }
                            TmButton(onClick = onAdd, modifier = Modifier.focusRequester(primary)) { Text(s.playbacktestAdd) }
                        } else {
                            TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) }
                        }
                    }
                }
                is State.Failed -> {
                    Body(if (state.fetching) s.playbacktestFailedFetch else s.playbacktestFailed)
                    PanelActions {
                        TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }
                        TmButton(onClick = onAgain, modifier = Modifier.focusRequester(primary)) { Text(s.commonTryAgain) }
                    }
                }
            }
        }
    }
    // Each step focuses its own step (Start, Add, Try again) or its only button, so the remote always
    // has somewhere to land; a Back press is the way out.
    LaunchedEffect(state::class) { runCatching { primary.requestFocus() } }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)

/** The muted picture the test plays, so the wait is something to watch. */
@OptIn(UnstableApi::class)
@Composable
private fun Preview(runner: PlaybackTestRunner) {
    Box(Modifier.fillMaxWidth().height(180.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Corner.Large)).background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            factory = { PlayerView(it).apply { useController = false } },
            update = { it.player = runner.player },
        )
    }
}

/** The plain sentence for each verdict. */
internal fun verdictWords(s: Strings, v: Verdict): String = when (v) {
    Verdict.CLEAN -> s.playbacktestVerdictClean
    Verdict.DECODER -> s.playbacktestVerdictDecoder
    Verdict.RENDER -> s.playbacktestVerdictRender
    Verdict.THERMAL -> s.playbacktestVerdictThermal
    Verdict.MEMORY -> s.playbacktestVerdictMemory
    Verdict.UNCLEAR -> s.playbacktestVerdictUnclear
    Verdict.COULD_NOT_PLAY -> s.playbacktestVerdictCouldNotPlay
    Verdict.TOO_SHORT -> s.playbacktestVerdictTooShort
}
