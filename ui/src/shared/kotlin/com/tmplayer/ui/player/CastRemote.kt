package com.tmplayer.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.cast.CastPhase
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.IconTip
import com.tmplayer.ui.components.SwapIcon
import com.tmplayer.ui.components.TipSide
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberToggleTick
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusDrawn
import com.tmplayer.ui.theme.focusRingColor
import java.util.Locale

/** Everything the cast remote draws, as of now. */
@Immutable
data class CastRemoteState(
    val title: String,
    val subtitle: String = "",
    /** The TV's own name: "Living Room TV". */
    val device: String,
    val phase: CastPhase = CastPhase.Starting,
    /** The connection to the TV dropped. */
    val lost: Boolean = false,
    /** What went wrong, said in a line, with Try again and Play here under it. */
    val error: String? = null,
    /** A word on how the video is sent, when it matters (the sound converted, none at all). */
    val note: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The play button shows Pause. */
    val showsPause: Boolean = true,
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** The TV takes no volume from a sender (an Android TV on HDMI-CEC): its own remote sets it. */
    val volumeFixed: Boolean = false,
    val hasAudioChoice: Boolean = false,
    val hasSubtitles: Boolean = false,
    val subtitlesOn: Boolean = false,
    /** The receiver's playback rate, or null when it cannot change it. */
    val speed: Float? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** A transcode for this TV, while it runs: the progress card with Stop converting. */
    val converting: com.tmplayer.cast.TranscodeProgress? = null,
)

/** What the cast remote's controls do; the platform's sender behind them. */
interface CastRemoteActions {
    fun back()
    fun devices()
    fun togglePlay()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun previous()
    fun next()
    fun audio()
    fun subtitles()
    fun speed()
    fun setVolume(level: Float)
    fun toggleMute()
    fun stop()
    fun reconnect()
    fun playHere()
    fun retry()

    /** Stop converting: the transcode and the TV's player stop, Try again and Play here offered. */
    fun cancelConvert() {}
}

/** The skips the remote's arrows make, as the player's own. */
const val CAST_SKIP_BACK_MS = 10_000L
const val CAST_SKIP_FORWARD_MS = 10_000L

/**
 * What the phone or tablet shows while the video plays on a TV, in place of the picture: the
 * video's name and the TV it is on with what the TV is doing, a line on how it is sent when that
 * matters, the timebar, play and the skips and the episode steps, the soundtrack, the subtitles,
 * the speed and the TV's volume, and Stop casting. Lost, stopped on the TV or refused, it offers
 * the way on (Reconnect or Try again, and Play here) in the same place.
 *
 * Two arrangements, chosen by the space: a column for a phone held upright or a tablet (the
 * controls in one width, centred), and a compact one for a phone on its side, where every control
 * is on screen at once without scrolling: the title bar, the timebar, and one row with the tracks
 * at the start, the transport in the middle and the volume at the end.
 *
 * It follows the app's theme (light or dark) and its tokens ([Tone]); every icon is named by its
 * tooltip, and every control answers its press.
 */
@Composable
fun CastRemote(state: CastRemoteState, actions: CastRemoteActions, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Tone.background)
            // Every touch stays here: none reaches the stopped picture's gestures underneath. A
            // pointer handler rather than a click, so a keyboard's first Tab and a screen reader
            // do not stop on an invisible screen-wide button (R8).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        val compact = maxHeight < 480.dp && maxWidth > maxHeight
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (compact) CompactLayout(state, actions, s) else TallLayout(state, actions, s)
        }
    }
}

// ---- arrangements --------------------------------------------------------------------------

@Composable
private fun TallLayout(state: CastRemoteState, actions: CastRemoteActions, s: Messages) {
    // The title bar at the top, Stop at the bottom and the rest centred between them, as equal
    // gaps; once that is taller than the screen (twice the font on a small phone) it scrolls
    // rather than squeezing Stop casting off the bottom (R8).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TitleBar(state, actions, s, showStop = false)
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                TvCard(state, actions, s)
                Spacer(Modifier.height(28.dp))
                Timebar(state, actions)
                Spacer(Modifier.height(12.dp))
                Transport(state, actions, s, Modifier.fillMaxWidth(), playSize = 72.dp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Tracks(state, actions, s)
                    Spacer(Modifier.width(8.dp))
                    Volume(state, actions, s, Modifier.weight(1f))
                }
            }
            StopButton(s, actions, Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun CompactLayout(state: CastRemoteState, actions: CastRemoteActions, s: Messages) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        TitleBar(state, actions, s, showStop = true)
        Spacer(Modifier.weight(1f))
        StatusPanel(state, actions, s, Modifier.padding(horizontal = 8.dp), inline = true)
        Spacer(Modifier.weight(1f))
        Timebar(state, actions, Modifier.padding(horizontal = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Tracks(state, actions, s) }
            Transport(state, actions, s, playSize = 60.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Volume(state, actions, s, Modifier.widthIn(max = 220.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ---- parts -----------------------------------------------------------------------------------

@Composable
private fun TitleBar(state: CastRemoteState, actions: CastRemoteActions, s: Messages, showStop: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(TmIcons.ArrowBack, s.commonBack, onClick = actions::back, side = TipSide.Below)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.subtitle.isNotBlank()) {
                Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showStop) StopButton(s, actions, compact = true)
        RoundIcon(TmIcons.Cast, s.castButton, tint = if (state.lost) Tone.muted else Tone.accent, onClick = actions::devices, side = TipSide.Below)
    }
}

/**
 * The TV, as a card: its glyph, "On Living Room TV" and what it is doing, and under it the note
 * and the way on when something went wrong.
 */
@Composable
private fun TvCard(state: CastRemoteState, actions: CastRemoteActions, s: Messages) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.surface)
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        StatusPanel(state, actions, s, Modifier, inline = false)
    }
}

/** The status: glyph, where, state; the note; the way on. [inline] keeps it to one block for a short screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPanel(state: CastRemoteState, actions: CastRemoteActions, s: Messages, modifier: Modifier, inline: Boolean) {
    val trouble = state.lost || state.error != null || state.phase == CastPhase.Stopped || state.phase == CastPhase.Failed
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint by animateColorAsState(if (trouble) Tone.muted else Tone.accent, tween(Motion.Normal), label = "tv")
            Box(
                Modifier.size(if (inline) 44.dp else 56.dp).clip(CircleShape).background(Tone.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TmIcons.Tv, null, tint = tint, modifier = Modifier.size(if (inline) 24.dp else 30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.lost) s.castLost(state.device) else s.castOnDevice(state.device),
                    style = if (inline) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StateLine(state, s)
            }
        }
        val note = state.note?.takeIf { !state.lost && state.error == null && state.phase != CastPhase.Failed }
        if (note != null) {
            Spacer(Modifier.height(if (inline) 6.dp else 12.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = if (inline) 2 else 4, overflow = TextOverflow.Ellipsis)
        }
        if (!trouble && state.converting != null) {
            Spacer(Modifier.height(if (inline) 6.dp else 12.dp))
            CastConvertingCard(state.converting, actions::cancelConvert)
        }
        if (trouble) {
            Spacer(Modifier.height(if (inline) 8.dp else 16.dp))
            // Whole pills wrap under each other at a large font rather than one squeezing the other.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.lost) Pill(s.castReconnect, primary = true, onClick = actions::reconnect)
                else Pill(s.commonTryAgain, primary = true, onClick = actions::retry)
                Pill(s.castPlayHere, primary = false, onClick = actions::playHere)
            }
        }
    }
}

/** What the TV is doing, in words, with a small spinner while it loads; it cross-fades as it changes. */
@Composable
private fun StateLine(state: CastRemoteState, s: Messages) {
    val line = when {
        state.lost -> null
        state.error != null -> state.error
        else -> when (state.phase) {
            CastPhase.Starting -> s.castStarting
            CastPhase.Loading, CastPhase.Buffering -> s.castStateBuffering
            CastPhase.Playing -> s.castStatePlaying
            CastPhase.Paused -> s.castStatePaused
            CastPhase.Ended -> s.castStateEnded
            CastPhase.Stopped -> s.castStateStopped
            CastPhase.Failed -> s.castLoadFailed
        }
    } ?: return
    val busy = !state.lost && state.error == null && state.phase in setOf(CastPhase.Starting, CastPhase.Loading, CastPhase.Buffering)
    AnimatedContent(line to busy, transitionSpec = { fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast)) }, label = "state") { (text, spinning) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (spinning) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Tone.muted, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null || state.phase == CastPhase.Failed) Tone.danger else Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The timebar and the times either side; a drag seeks once, where it is let go. */
@Composable
private fun Timebar(state: CastRemoteState, actions: CastRemoteActions, modifier: Modifier = Modifier) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = scrubbing?.let { (it * duration).toLong() } ?: state.positionMs
    val enabled = state.durationMs > 0 && !state.lost && state.error == null && state.phase != CastPhase.Starting
    val formatter = LocalStrings.current.formatter
    // The phone as a remote is often held without looking: the drag is felt taking hold and letting go.
    val haptic = com.tmplayer.ui.components.rememberHaptics()
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = (shown.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = {
                if (scrubbing == null) haptic(com.tmplayer.ui.components.Haptic.GestureStart)
                scrubbing = it
            },
            onValueChangeFinished = {
                scrubbing?.let { actions.seekTo((it * duration).toLong()) }
                scrubbing = null
                haptic(com.tmplayer.ui.components.Haptic.GestureEnd)
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Tone.accent,
                activeTrackColor = Tone.accent,
                inactiveTrackColor = Tone.surfaceHigh,
                disabledThumbColor = Tone.muted,
                disabledActiveTrackColor = Tone.muted,
                disabledInactiveTrackColor = Tone.surfaceHigh,
            ),
            modifier = Modifier.fillMaxWidth().height(36.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(formatter.clock(shown), style = MaterialTheme.typography.labelMedium, color = Tone.muted)
            Spacer(Modifier.weight(1f))
            Text(if (state.durationMs > 0) formatter.clock(state.durationMs) else "", style = MaterialTheme.typography.labelMedium, color = Tone.muted)
        }
    }
}

/** Previous episode, back, play or pause, forward, next episode. */
@Composable
private fun Transport(state: CastRemoteState, actions: CastRemoteActions, s: Messages, modifier: Modifier = Modifier, playSize: Dp) {
    val live = !state.lost && state.phase != CastPhase.Starting
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        RoundIcon(TmIcons.SkipPrevious, s.playerPreviousEpisode, enabled = state.hasPrevious, onClick = actions::previous)
        RoundIcon(TmIcons.Rewind, s.notifySeekBack((CAST_SKIP_BACK_MS / 1000).toInt()), enabled = live, onClick = { actions.seekBy(-CAST_SKIP_BACK_MS) })
        PlayButton(state, s, playSize, live, actions::togglePlay)
        RoundIcon(TmIcons.FastForward, s.notifySeekForward((CAST_SKIP_FORWARD_MS / 1000).toInt()), enabled = live, onClick = { actions.seekBy(CAST_SKIP_FORWARD_MS) })
        RoundIcon(TmIcons.SkipNext, s.playerNextEpisode, enabled = state.hasNext, onClick = actions::next)
    }
}

@Composable
private fun PlayButton(state: CastRemoteState, s: Messages, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val label = if (state.showsPause) s.commonPause else s.commonPlay
    val interactions = remember { MutableInteractionSource() }
    val loading = state.phase == CastPhase.Starting || state.phase == CastPhase.Loading
    IconTip(label) {
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .size(size)
                .pressScale(interactions)
                .focusDrawn(interactions.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
                .clip(CircleShape)
                .background(if (enabled) Tone.hero else Tone.surfaceHigh)
                .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(size * 0.42f), color = if (enabled) Tone.onHero else Tone.muted, strokeWidth = 2.5.dp)
            } else {
                SwapIcon(if (state.showsPause) TmIcons.PauseSolid else TmIcons.PlaySolid, tint = if (enabled) Tone.onHero else Tone.muted, size = size * 0.46f)
            }
        }
    }
}

/** The soundtrack, the subtitles and the speed. */
@Composable
private fun RowScope.Tracks(state: CastRemoteState, actions: CastRemoteActions, s: Messages) {
    val live = !state.lost && state.phase != CastPhase.Starting
    if (state.hasAudioChoice) RoundIcon(TmIcons.Audio, s.playerAudio, enabled = live, onClick = actions::audio)
    // Left out altogether while casting goes without subtitles (CAST_SUBTITLES), the row closing up.
    if (state.hasSubtitles) {
        RoundIcon(
            TmIcons.Subtitles,
            s.tracksSubtitles,
            enabled = live,
            selected = state.subtitlesOn,
            selectable = true,
            onClick = actions::subtitles,
        )
    }
    state.speed?.let { speed ->
        val label = s.playerPlaybackSpeed
        val text = speedText(speed)
        IconTip(label) {
            val interactions = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(48.dp)
                    .pressScale(interactions)
                    .focusDrawn(interactions.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
                    .clip(CircleShape)
                    .clickable(interactionSource = interactions, indication = null, enabled = live, role = Role.Button, onClick = actions::speed)
                    .semantics { contentDescription = "$label, $text" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !live -> Tone.muted.copy(alpha = 0.5f)
                        speed != 1f -> Tone.accent
                        else -> Tone.text
                    },
                )
            }
        }
    }
}

private fun speedText(speed: Float): String {
    val t = if (speed % 1f == 0f) String.format(Locale.ROOT, "%.0f", speed) else String.format(Locale.ROOT, "%.2f", speed).trimEnd('0')
    return "$t×"
}

/** The TV's volume: the mute button and a slider, or a word that the TV's own remote sets it. */
@Composable
private fun Volume(state: CastRemoteState, actions: CastRemoteActions, s: Messages, modifier: Modifier = Modifier) {
    val tick = rememberToggleTick()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val silent = state.muted || state.volume <= 0f
        if (state.volumeFixed) {
            Icon(TmIcons.VolumeUp, null, tint = Tone.muted, modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
            Text(s.castVolumeFixed, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        RoundIcon(
            if (silent) TmIcons.VolumeOff else TmIcons.VolumeUp,
            if (state.muted) s.castUnmuteTv else s.castMuteTv,
            enabled = !state.lost,
            swap = true,
            onClick = {
                tick(!state.muted)
                actions.toggleMute()
            },
        )
        val volumeHaptic = com.tmplayer.ui.components.rememberHaptics()
        Slider(
            value = if (state.muted) 0f else state.volume.coerceIn(0f, 1f),
            // A light tick at each tenth of the television's volume crossed, not at every pixel.
            onValueChange = { next ->
                val before = if (state.muted) 0 else (state.volume * VOLUME_STEPS).toInt()
                if ((next * VOLUME_STEPS).toInt() != before) volumeHaptic(com.tmplayer.ui.components.Haptic.FrequentTick)
                actions.setVolume(next)
            },
            enabled = !state.lost,
            colors = SliderDefaults.colors(
                thumbColor = Tone.text,
                activeTrackColor = Tone.text,
                inactiveTrackColor = Tone.surfaceHigh,
                disabledThumbColor = Tone.muted,
                disabledActiveTrackColor = Tone.muted,
            ),
            modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = s.castTvVolume },
        )
    }
}

@Composable
private fun StopButton(s: Messages, actions: CastRemoteActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(interactions, depth = 0.97f)
            .focusDrawn(interactions.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
            .clip(CircleShape)
            .background(Tone.surface)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = actions::stop)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(TmIcons.Close, null, tint = Tone.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.castStop, style = MaterialTheme.typography.labelLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pressScale(interactions, depth = 0.97f)
            .focusDrawn(interactions.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
            .clip(CircleShape)
            .background(if (primary) Tone.hero else Tone.surfaceHigh)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) Tone.onHero else Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon button named by its tooltip; it sinks under the finger, and a [selected] one is filled. */
@Composable
private fun RoundIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    selected: Boolean = false,
    /** Says [selected] out loud: for a button that is on or off, rather than one that only acts. */
    selectable: Boolean = false,
    swap: Boolean = false,
    side: TipSide = TipSide.Above,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) Tone.focusFill else Color.Transparent, tween(Motion.Fast), label = "fill")
    val color = when {
        !enabled -> Tone.muted.copy(alpha = 0.45f)
        selected -> Tone.onFocusFill
        tint != Color.Unspecified -> tint
        else -> Tone.text
    }
    IconTip(label, side = side) {
        Box(
            Modifier
                .size(48.dp)
                .pressScale(interactions)
                .focusDrawn(interactions.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
                .clip(CircleShape)
                .background(fill)
                .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    // The subtitles button is a switch for a screen reader too: on or off, not only filled.
                    if (selectable) this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            if (swap) SwapIcon(icon, tint = color, size = 24.dp) else Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        }
    }
}

/** The volume steps the cast remote's slider ticks at: tenths. */
private const val VOLUME_STEPS = 10
