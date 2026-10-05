package com.tmplayer.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** What a menu entry asks for, beyond the plain [PlayerAction]s. */
internal sealed interface MenuAction {
    data class Do(val action: PlayerAction) : MenuAction
    data class Track(val type: TrackType, val track: MediaTrack?) : MenuAction
    data class Speed(val speed: Float) : MenuAction
    data class Shape(val scale: VideoScale) : MenuAction
    data object ToggleDownmix : MenuAction
    data object StartOver : MenuAction
    data object ToggleIgnoreClicks : MenuAction
    data object CopyLink : MenuAction
    data object Download : MenuAction
    data object OpenElsewhere : MenuAction
    data object Details : MenuAction
    data object Shortcuts : MenuAction
    data object ToggleWatched : MenuAction
}

private val Scrim = Color(0xB3000000)

/**
 * The picture itself as a click target (B2.3): a single click plays or pauses after 300 ms, so a
 * double click (fullscreen) does not flicker the picture first; right click opens the menu at the
 * cursor; the mouse's back button goes back.
 */
@Composable
internal fun BoxScope.VideoGestures(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: (Offset) -> Unit,
    onMouseBack: () -> Unit,
    scope: CoroutineScope,
) {
    val click by rememberUpdatedState(onClick)
    val double by rememberUpdatedState(onDoubleClick)
    val right by rememberUpdatedState(onRightClick)
    val back by rememberUpdatedState(onMouseBack)
    var pending by remember { mutableStateOf<Job?>(null) }
    Box(
        Modifier.matchParentSize().testTag("video").pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    val at = event.changes.firstOrNull()?.position ?: Offset.Zero
                    when {
                        event.buttons.isSecondaryPressed -> right(at)
                        event.buttons.isBackPressed -> back()
                        event.buttons.isPrimaryPressed -> {
                            val waiting = pending
                            if (waiting != null && waiting.isActive) {
                                waiting.cancel()
                                pending = null
                                double()
                            } else {
                                pending = scope.launch {
                                    delay(DOUBLE_CLICK_MS)
                                    pending = null
                                    click()
                                }
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        },
    )
}

private const val DOUBLE_CLICK_MS = 300L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.PlayerOverlay(
    visible: Boolean,
    status: PlaybackStatus,
    title: String,
    subtitle: String,
    episodes: Episodes,
    tracks: List<MediaTrack>,
    downloaded: Float?,
    showRemaining: Boolean,
    fullscreen: Boolean,
    menu: MenuAt?,
    ignoreClicks: Boolean,
    miniPlayerAvailable: Boolean,
    alwaysOnTopAvailable: Boolean,
    fromTelegram: Boolean,
    /** Whether Save to Downloads and Open in another app are offered; see [PlayerMedia.savable]. */
    savable: Boolean = true,
    /** "Mark as watched" or "Mark as unwatched" for the menu, or null to leave the line out. */
    watchedLabel: String? = null,
    onHoverControls: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onEpisode: (MediaItem) -> Unit,
    onToggleRemaining: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleScale: () -> Unit,
    onVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onMiniPlayer: () -> Unit,
    onOpenMenu: (MenuAt) -> Unit,
    onCloseMenu: () -> Unit,
    onMenuAction: (MenuAction) -> Unit,
) {
    // The right click menu hangs at the cursor whether or not the controls are up.
    if (menu?.anchor == MenuAt.Anchor.Cursor) {
        Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }.size(1.dp)) {
            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, onOpenMenu, onCloseMenu, onMenuAction)
        }
    }

    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
        Box(Modifier.fillMaxSize()) {
            // The 30 % tint over the whole picture, so white glyphs read on a bright frame.
            Box(Modifier.matchParentSize().background(Color(0x4D000000)))

            // Top bar.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                    .hoverReport(onHoverControls)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(PlayerIcons.ArrowBack, "Back (Esc)", onBack)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                WallClock()
                Spacer(Modifier.width(8.dp))
                Box {
                    OverlayButton(PlayerIcons.MoreVert, "More", onClick = { onOpenMenu(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) })
                    if (menu?.anchor == MenuAt.Anchor.Overflow) {
                        PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, onOpenMenu, onCloseMenu, onMenuAction)
                    }
                }
            }

            // The download chip, top right under the bar.
            if (downloaded != null && downloaded < 1f) {
                Chip("Downloaded ${(downloaded * 100).toInt()}%", Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp))
            }

            // Centre cluster.
            Row(
                Modifier.align(Alignment.Center).hoverReport(onHoverControls),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val previous = episodes.previous
                val next = episodes.next
                if (previous != null) {
                    OverlayButton(PlayerIcons.SkipPrevious, episodeLabel("Previous", previous) + " (Shift+P)", { onEpisode(previous) }, size = 48)
                }
                SkipButton(forward = false) { onSeekBy(-PlayerKeys.SEEK_MEDIUM_MS) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (status.buffering) {
                        CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(if (status.playing) PlayerIcons.Pause else PlayerIcons.Play, if (status.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
                SkipButton(forward = true) { onSeekBy(PlayerKeys.SEEK_MEDIUM_MS) }
                if (next != null) {
                    OverlayButton(PlayerIcons.SkipNext, episodeLabel("Next", next) + " (Shift+N)", { onEpisode(next) }, size = 48)
                }
            }

            // Bottom: times, bar, button row.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                    .hoverReport(onHoverControls)
                    .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(SeekMath.clock(status.positionMs), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (showRemaining) SeekMath.remaining(status.positionMs, status.durationMs) else SeekMath.clock(status.durationMs),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleRemaining).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                TimeBar(status.positionMs, status.durationMs, status.bufferedMs, onSeekTo)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OverlayButton(PlayerIcons.Subtitles, "Subtitles (S, C)", onClick = { onOpenMenu(MenuAt(MenuPage.Subtitles, MenuAt.Anchor.Subtitles)) })
                        if (menu?.anchor == MenuAt.Anchor.Subtitles) {
                            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, onOpenMenu, onCloseMenu, onMenuAction)
                        }
                    }
                    Box {
                        OverlayButton(PlayerIcons.Audio, "Audio (A)", onClick = { onOpenMenu(MenuAt(MenuPage.Audio, MenuAt.Anchor.Audio)) })
                        if (menu?.anchor == MenuAt.Anchor.Audio) {
                            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, onOpenMenu, onCloseMenu, onMenuAction)
                        }
                    }
                    Tip("Speed (] and [)") {
                        Box(
                            Modifier.height(36.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onCycleSpeed).padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(SeekMath.speedLabel(status.speed), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                    OverlayButton(PlayerIcons.AspectRatio, "Picture shape: ${status.scale.label}", onCycleScale)
                    Spacer(Modifier.weight(1f))
                    VolumeControl(status.volume, status.muted, onVolume, onToggleMute)
                    OverlayButton(PlayerIcons.PictureInPicture, "Mini player (Ctrl+P)", onMiniPlayer)
                    OverlayButton(
                        if (fullscreen) PlayerIcons.FullscreenExit else PlayerIcons.Fullscreen,
                        if (fullscreen) "Exit fullscreen (F)" else "Fullscreen (F)",
                        onToggleFullscreen,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.hoverReport(onHover: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onHover(true) }
    .onPointerEvent(PointerEventType.Exit) { onHover(false) }

private fun episodeLabel(direction: String, item: MediaItem): String {
    val code = MediaName.parse(item.fileName.ifBlank { item.title }).episodeCode ?: return direction
    return "$direction $code"
}

@Composable
private fun WallClock() {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = LocalTime.now()
        }
    }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xE6202022)) {
                Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, (-36).dp)),
    ) { content() }
}

@Composable
internal fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 40) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Tip(label) {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
                .hoverable(hover)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

/** Back or forward ten seconds, with the figure inside the arrow as on the phone. */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Tip(if (forward) "Forward 10 s (L)" else "Back 10 s (J)") {
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
                .hoverable(hover)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PlayerIcons.Replay,
                if (forward) "Forward 10 seconds" else "Back 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(36.dp).graphicsLayer { if (forward) scaleX = -1f },
            )
            Text("10", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = 2.dp))
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeControl(volume: Int, muted: Boolean, onVolume: (Int) -> Unit, onToggleMute: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(hovered) {
            Slider(
                value = if (muted) 0f else volume / 100f,
                onValueChange = { onVolume((it * 100).roundToInt()) },
                modifier = Modifier.width(110.dp).padding(end = 4.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
            )
        }
        val icon = when {
            muted || volume == 0 -> PlayerIcons.VolumeOff
            volume < 50 -> PlayerIcons.VolumeDown
            else -> PlayerIcons.VolumeUp
        }
        OverlayButton(icon, if (muted) "Unmute (M)" else "Mute (M)", onToggleMute)
    }
}

/**
 * The timebar: the played run, the buffered run ahead of it, and on hover the time under the
 * cursor (B2.3). A press or drag moves a preview; the seek happens on release, so dragging across
 * an hour does not send mpv sixty seeks.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TimeBar(position: Long, duration: Long, buffered: Long, onSeekTo: (Long) -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeekTo)
    val length by rememberUpdatedState(duration)
    Box(
        Modifier.fillMaxWidth().height(28.dp).testTag("timebar")
            .onSizeChanged { width = it.width.toFloat() }
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Enter) { hoverX = it.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragX = down.position.x
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        dragX = change.position.x
                        change.consume()
                    }
                    dragX?.let { seek(SeekMath.timeAt(it, width, length)) }
                    dragX = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shownX = dragX
        val played = if (shownX != null) (shownX / width).coerceIn(0f, 1f) else SeekMath.fraction(position, duration)
        val ahead = SeekMath.fraction(buffered, duration)
        val thick = hoverX != null || dragX != null
        val primary = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(if (thick) 6.dp else 4.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color(0x40FFFFFF), cornerRadius = r)
            if (ahead > played) drawRoundRect(Color(0x66FFFFFF), size = Size(size.width * ahead, size.height), cornerRadius = r)
            drawRoundRect(primary, size = Size(size.width * played, size.height), cornerRadius = r)
            val thumb = if (thick) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(primary, thumb, Offset(size.width * played, size.height / 2))
        }
        val labelX = dragX ?: hoverX
        if (labelX != null && duration > 0) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { IntOffset(labelX.roundToInt() - 32.dp.roundToPx(), -28.dp.roundToPx()) }
                    .width(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Chip(SeekMath.clock(SeekMath.timeAt(labelX, width, duration)))
            }
        }
    }
}

/** One menu for the right click, the overflow button and the two track buttons, paged. */
@Composable
private fun PlayerMenu(
    menu: MenuAt,
    status: PlaybackStatus,
    tracks: List<MediaTrack>,
    fullscreen: Boolean,
    ignoreClicks: Boolean,
    miniPlayerAvailable: Boolean,
    alwaysOnTopAvailable: Boolean,
    fromTelegram: Boolean,
    savable: Boolean,
    watchedLabel: String?,
    onOpenMenu: (MenuAt) -> Unit,
    onClose: () -> Unit,
    onAction: (MenuAction) -> Unit,
) {
    fun pick(action: MenuAction) {
        onClose()
        onAction(action)
    }
    fun page(to: MenuPage) = onOpenMenu(menu.copy(page = to))

    @Composable
    fun Entry(text: String, checked: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = onClick,
            leadingIcon = { Box(Modifier.size(18.dp)) { if (checked) Icon(PlayerIcons.Check, null, Modifier.size(18.dp)) } },
            trailingIcon = trailing?.let { { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) } },
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onClose, modifier = Modifier.widthIn(min = 240.dp, max = 380.dp)) {
        if (menu.page != MenuPage.Main && menu.anchor != MenuAt.Anchor.Subtitles && menu.anchor != MenuAt.Anchor.Audio) {
            Entry("Back") { page(MenuPage.Main) }
            HorizontalDivider()
        }
        when (menu.page) {
            MenuPage.Main -> {
                Entry(if (status.playing) "Pause" else "Play", trailing = "Space") { pick(MenuAction.Do(PlayerAction.TogglePlay)) }
                val audio = tracks.firstOrNull { it.type == TrackType.Audio && it.selected }
                Entry("Audio", trailing = audio?.label?.take(18) ?: "") { page(MenuPage.Audio) }
                val sub = tracks.firstOrNull { it.type == TrackType.Subtitle && it.selected }
                Entry("Subtitles", trailing = sub?.label?.take(18) ?: "Off") { page(MenuPage.Subtitles) }
                Entry("Speed", trailing = SeekMath.speedLabel(status.speed)) { page(MenuPage.Speed) }
                Entry("Picture shape", trailing = status.scale.label) { page(MenuPage.Shape) }
                HorizontalDivider()
                Entry(if (fullscreen) "Exit fullscreen" else "Fullscreen", trailing = "F") { pick(MenuAction.Do(PlayerAction.ToggleFullscreen)) }
                if (alwaysOnTopAvailable) Entry("Always on top", trailing = "Ctrl+T") { pick(MenuAction.Do(PlayerAction.AlwaysOnTop)) }
                if (miniPlayerAvailable) Entry("Mini player", trailing = "Ctrl+P") { pick(MenuAction.Do(PlayerAction.MiniPlayer)) }
                Entry("Downmix to stereo", checked = status.downmix) { pick(MenuAction.ToggleDownmix) }
                Entry("Ignore clicks on the video", checked = ignoreClicks) { pick(MenuAction.ToggleIgnoreClicks) }
                HorizontalDivider()
                Entry("Start over") { pick(MenuAction.StartOver) }
                if (fromTelegram) {
                    Entry("Copy link") { pick(MenuAction.CopyLink) }
                    if (savable) {
                        Entry("Save to Downloads") { pick(MenuAction.Download) }
                        Entry("Open in another app") { pick(MenuAction.OpenElsewhere) }
                    }
                }
                if (watchedLabel != null) Entry(watchedLabel) { pick(MenuAction.ToggleWatched) }
                Entry("Playback details", trailing = "I") { pick(MenuAction.Details) }
                Entry("Keyboard shortcuts", trailing = "?") { pick(MenuAction.Shortcuts) }
            }
            MenuPage.Audio -> {
                val list = tracks.filter { it.type == TrackType.Audio }
                if (list.isEmpty()) Entry("No audio tracks") { onClose() }
                list.forEach { t -> Entry(t.label, checked = t.selected) { pick(MenuAction.Track(TrackType.Audio, t)) } }
            }
            MenuPage.Subtitles -> {
                val list = tracks.filter { it.type == TrackType.Subtitle }
                Entry("Off", checked = list.none { it.selected }) { pick(MenuAction.Track(TrackType.Subtitle, null)) }
                list.forEach { t -> Entry(t.label, checked = t.selected) { pick(MenuAction.Track(TrackType.Subtitle, t)) } }
            }
            MenuPage.Speed -> PlaybackSpeed.CHOICES.forEach { s ->
                Entry(PlaybackSpeed.label(s), checked = kotlin.math.abs(s - status.speed) < 0.001f) { pick(MenuAction.Speed(s)) }
            }
            MenuPage.Shape -> VideoScale.entries.forEach { s ->
                Entry(s.label, checked = s == status.scale) { pick(MenuAction.Shape(s)) }
            }
        }
    }
}

/** The flashes of A4.1 and A4.2, the volume HUD, and the spinner for a stall with the controls down. */
@Composable
internal fun BoxScope.FeedbackLayer(flash: Flash?, spinner: Boolean, chip: String?) {
    if (spinner) {
        CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White, strokeWidth = 3.dp)
    }
    if (chip != null) Chip(chip, Modifier.align(Alignment.TopEnd).padding(16.dp))
    val f = flash ?: return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(f.id) {
        alpha.snapTo(1f)
        scale.snapTo(0.8f)
        launch { scale.animateTo(1f, tween(250)) }
        val hold = when (f.kind) {
            Flash.Kind.Play, Flash.Kind.Pause -> 150L
            else -> 650L
        }
        delay(hold)
        alpha.animateTo(0f, tween(if (f.kind == Flash.Kind.Play || f.kind == Flash.Kind.Pause) 250 else 300))
    }
    val layer = Modifier.graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
    when (f.kind) {
        Flash.Kind.Play, Flash.Kind.Pause -> Box(
            layer.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color(0x80000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (f.kind == Flash.Kind.Play) PlayerIcons.Play else PlayerIcons.Pause, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Flash.Kind.SeekBack, Flash.Kind.SeekForward -> {
            val back = f.kind == Flash.Kind.SeekBack
            Box(
                Modifier.align(if (back) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight().fillMaxWidth(0.3f)
                    .graphicsLayer { this.alpha = alpha.value }
                    .background(
                        Brush.horizontalGradient(
                            if (back) listOf(Color(0x33FFFFFF), Color.Transparent) else listOf(Color.Transparent, Color(0x33FFFFFF)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (back) "<<<" else ">>>", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(f.text, color = Color.White, fontSize = 15.sp)
                }
            }
        }
        Flash.Kind.Volume -> Box(
            layer.align(Alignment.TopCenter).padding(top = 80.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x99000000))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (f.text == "Muted" || f.text == "0%") PlayerIcons.VolumeOff else PlayerIcons.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(f.text, color = Color.White, fontSize = 15.sp)
            }
        }
        Flash.Kind.Text -> Box(
            layer.align(Alignment.TopCenter).padding(top = 80.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x99000000))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(f.text, color = Color.White, fontSize = 15.sp) }
    }
}

/** "Resuming from 1:02:14" with Start over beside it (A4.8), for eight seconds. */
@Composable
internal fun BoxScope.ResumeNotice(from: Long, lifted: Boolean, onStartOver: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(from) {
        delay(8_000)
        onTimeout()
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (lifted) 120.dp else 24.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color(0xCC1C1C1E)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Resuming from ${SeekMath.clock(from)}", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onStartOver) { Text("Start over") }
    }
}

/** A4.7: the next episode, offered in the last half minute. */
@Composable
internal fun BoxScope.NextUpCard(next: MediaItem, secondsLeft: Int, lifted: Boolean, onPlayNow: () -> Unit, onHide: () -> Unit) {
    val code = MediaName.parse(next.fileName.ifBlank { next.title }).episodeCode
    AnimatedVisibility(
        true,
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (lifted) 120.dp else 24.dp),
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xE61C1C1E), modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Next: ${code ?: next.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Next episode in $secondsLeft", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onPlayNow) { Text("Play now") }
                    TextButton(onClick = onHide) { Text("Hide") }
                }
            }
        }
    }
}

/** B3.4 item 4: what a tester's report needs, refreshed while open. */
@Composable
internal fun BoxScope.DetailsPanel(rows: () -> List<Pair<String, String>>, onClose: () -> Unit) {
    var lines by remember { mutableStateOf(rows()) }
    val read by rememberUpdatedState(rows)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            lines = read()
        }
    }
    Surface(
        Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp).width(400.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xE61C1C1E),
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playback details", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OverlayButton(PlayerIcons.Close, "Close (I)", onClose, size = 32)
            }
            Spacer(Modifier.height(8.dp))
            lines.forEach { (label, value) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(150.dp))
                    Text(value, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The loading, error, countdown and finished sheets over the picture. */
@Composable
internal fun BoxScope.StatusSheet(
    phase: Phase,
    title: String,
    subtitle: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onWatchAgain: () -> Unit,
    onPlayNext: (MediaItem) -> Unit,
    onCancelNext: () -> Unit,
) {
    if (phase == Phase.Playing) return
    Box(Modifier.matchParentSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            when (phase) {
                is Phase.Loading -> {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(phase.message, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) { Text("Back") }
                }
                is Phase.Failed -> {
                    Text(phase.message, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = onRetry) { Text("Try again") }
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                }
                is Phase.Countdown -> {
                    val code = MediaName.parse(phase.next.fileName.ifBlank { phase.next.title }).episodeCode
                    Text("Next: ${code ?: phase.next.title}", color = Color.White, fontSize = 17.sp)
                    Text("Starting in ${phase.seconds}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = { onPlayNext(phase.next) }) { Text("Play now") }
                        TextButton(onClick = onCancelNext) { Text("Cancel") }
                    }
                }
                Phase.Finished -> {
                    Text("That's the end.", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = onWatchAgain) { Text("Watch again") }
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                }
                Phase.Playing -> Unit
            }
        }
    }
}

@Composable
internal fun BoxScope.ShortcutSheet(onClose: () -> Unit, mac: Boolean = false, wheelSeeks: Boolean = false) {
    Box(Modifier.matchParentSize().background(Color(0x99000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xF21C1C1E), modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Keyboard shortcuts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                PlayerKeys.sheet(mac, wheelSeeks).forEach { (what, keys) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(what, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(keys, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
