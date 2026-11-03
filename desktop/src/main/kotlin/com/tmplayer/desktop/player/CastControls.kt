package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.cast.CastPhase
import com.tmplayer.desktop.cast.CastPlaybackEngine
import com.tmplayer.desktop.cast.DesktopCast
import com.tmplayer.desktop.cast.ReceiverKind
import com.tmplayer.ui.components.TmDropdownMenu
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * The player bar's Cast button and its popover: the Chromecasts and Google TVs on the network,
 * found while it is open, the one in use ticked, and Stop casting under them while a cast is on.
 */
@Composable
internal fun CastButton() {
    val s = LocalStrings.current
    val state by DesktopCast.state.collectAsState()
    val devices by DesktopCast.discovery.devices.collectAsState()
    var open by remember { mutableStateOf(false) }
    var waitedLong by remember { mutableStateOf(false) }
    var tries by remember { mutableStateOf(0) }
    DisposableEffect(open) {
        if (open) DesktopCast.discovery.start()
        onDispose { if (open) DesktopCast.discovery.stop() }
    }
    LaunchedEffect(open, tries) {
        waitedLong = false
        if (open) {
            delay(NONE_FOUND_AFTER_MS)
            waitedLong = true
        }
    }
    val connected = (state as? DesktopCast.State.Connected)?.device
    val label = connected?.let { s.castCastingTo(it.name) } ?: s.castButton
    Box {
        Box {
            OverlayButton(TmIcons.Cast, label, onClick = { open = true })
            // The button says a cast is on, as the phone's does, with a dot in the corner.
            if (connected != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(CastOn, CircleShape))
            }
        }
        if (open) {
            TmDropdownMenu(expanded = true, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 260.dp, max = 380.dp)) {
                Text(
                    s.castDevices,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (devices.isEmpty()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!waitedLong) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(if (waitedLong) s.castNoneFound else s.castSearching, fontSize = 14.sp, maxLines = 8)
                    }
                    if (waitedLong) {
                        TextButton(
                            onClick = {
                                DesktopCast.discovery.restart()
                                tries++
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(s.commonTryAgain) }
                    }
                }
                for (device in devices) {
                    val busy = (state as? DesktopCast.State.Connecting)?.device?.id == device.id
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val line = if (device.kind == ReceiverKind.Dlna) {
                                    listOf(s.castDlnaLabel, device.model).filter { it.isNotBlank() }.joinToString(" · ")
                                } else {
                                    device.status.takeIf { it.isNotBlank() } ?: device.model
                                }
                                if (line.isNotBlank()) Text(line, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingIcon = { Icon(TmIcons.Tv, null, Modifier.size(20.dp)) },
                        trailingIcon = {
                            when {
                                busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                connected?.id == device.id -> Icon(TmIcons.Check, null, Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            DesktopCast.connect(device)
                            open = false
                        },
                    )
                }
                if (connected != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(s.castStop) },
                        leadingIcon = { Icon(TmIcons.Close, null, Modifier.size(18.dp)) },
                        onClick = {
                            DesktopCast.disconnect()
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the picture area shows while the video is on a TV: one compact card in the middle, the TV's
 * glyph, "On Living Room TV" and what the TV is doing (which follows the TV's own status, a pause
 * or a stall on the TV included), any word on how it is sent (the sound converted, a soundtrack
 * the TV cannot play), and, when the connection is lost, the TV stopped or refused the video, the
 * way on: Reconnect or Try again, and Play here. The player bar under it drives the TV as it
 * drives the picture here: play, seek, the soundtrack and subtitle menus, the speed, the volume.
 */
@Composable
internal fun BoxScope.CastingPanel(
    cast: CastPlaybackEngine,
    status: PlaybackStatus,
    onPlayHere: () -> Unit,
) {
    val s = LocalStrings.current
    val state by DesktopCast.state.collectAsState()
    val note by cast.note.collectAsState()
    val phase by cast.phase.collectAsState()
    val volume by cast.receiver.volume.collectAsState()
    val name = cast.receiver.name
    val lost = state is DesktopCast.State.Lost || state is DesktopCast.State.Failed
    val trouble = lost || status.error != null || phase == CastPhase.Stopped || phase == CastPhase.Failed
    Column(
        Modifier
            .align(Alignment.Center)
            .widthIn(min = 320.dp, max = 440.dp)
            .padding(24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.surface.copy(alpha = 0.92f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Tone.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(TmIcons.Tv, null, tint = if (trouble) Tone.muted else CastOn, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (lost) s.castLost(name) else s.castOnDevice(name),
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = when {
                    lost -> null
                    status.error != null -> status.error
                    else -> when (phase) {
                        CastPhase.Starting -> s.castStarting
                        CastPhase.Loading, CastPhase.Buffering -> s.castStateBuffering
                        CastPhase.Playing -> s.castStatePlaying
                        CastPhase.Paused -> s.castStatePaused
                        CastPhase.Ended -> s.castStateEnded
                        CastPhase.Stopped -> s.castStateStopped
                        CastPhase.Failed -> s.castLoadFailed
                    }
                }
                if (line != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!trouble && (phase == CastPhase.Starting || phase == CastPhase.Loading || phase == CastPhase.Buffering)) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Tone.muted)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (status.error != null || phase == CastPhase.Failed) Tone.danger else Tone.muted)
                    }
                }
            }
        }
        note?.takeIf { !lost }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
        if (volume.fixed && !lost) Text(s.castVolumeFixed, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        if (trouble) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lost) Button(onClick = { DesktopCast.reconnect() }) { Text(s.castReconnect) }
                else Button(onClick = { cast.retry() }) { Text(s.commonTryAgain) }
                TextButton(onClick = onPlayHere) { Text(s.castPlayHere) }
            }
        }
    }
}

/** The Cast glyph's colour while a cast is on: Google's cast blue, which viewers know it by. */
private val CastOn = Color(0xFF8AB4F8)
private const val NONE_FOUND_AFTER_MS = 8_000L
