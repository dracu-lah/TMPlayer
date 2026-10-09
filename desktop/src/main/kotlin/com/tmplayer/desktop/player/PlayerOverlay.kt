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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.components.TmDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.SleepTimer
import com.tmplayer.player.SubtitlePosition
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** What a menu entry asks for, beyond the plain [PlayerAction]s. */
internal sealed interface MenuAction {
    data class Do(val action: PlayerAction) : MenuAction
    data class Track(val type: TrackType, val track: MediaTrack?) : MenuAction
    data class Speed(val speed: Float) : MenuAction
    data class Shape(val scale: VideoScale) : MenuAction
    data object ToggleDownmix : MenuAction
    data object ToggleVolumeBoost : MenuAction

    /** Minutes from now, [SleepTimer.END_OF_VIDEO], or null to turn the timer off. */
    data class Sleep(val minutes: Int?) : MenuAction

    /** One step later (1) or earlier (-1), or 0 for back in step. The menu stays open for more. */
    data class SubtitleDelay(val direction: Int) : MenuAction
    data class AudioDelay(val direction: Int) : MenuAction

    /** A new subtitle look, previewed on the picture while the menu stays open. */
    data class SubtitleLook(val style: SubtitleStyle) : MenuAction
    data object StartOver : MenuAction
    data object ToggleIgnoreClicks : MenuAction
    data object CopyLink : MenuAction
    data object Download : MenuAction
    data object OpenElsewhere : MenuAction
    data object Details : MenuAction
    data object Shortcuts : MenuAction
    data object ToggleWatched : MenuAction

    /** The subtitle menu's "Search online", in a build with the OpenSubtitles key. */
    data object SearchOnline : MenuAction
}

/**
 * The scrims behind the top bar and the bottom cluster. Three stops rather than a straight ramp,
 * so the band the text sits in (the title, the times) is still at least 60 per cent dark and white
 * text holds AA contrast on a white frame, and only then fades out.
 */
private val TopScrim = Brush.verticalGradient(0f to Color(0xD9000000), 0.6f to Color(0x8C000000), 1f to Color.Transparent)
private val BottomScrim = Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x99000000), 1f to Color(0xE6000000))

/**
 * The player's tonal surfaces, the Android player's player_tonal and player_tonal_button: a
 * near-black surface container let partly through. The caption tone carries small text (chips,
 * flashes); the button tone sits under the round centre buttons.
 */
internal val PlayerTonal = Color(0xB3101014)
internal val PlayerTonalButton = Color(0x8C101014)
private val OnPlayerTonal = Color(0xF2FFFFFF)

/** The A-B repeat on the timebar: amber, apart from the played blue and the buffered grey. */
private val LoopColor = Color(0xFFFFC107)

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
    /** Whether [downloaded] counts a real download ("Downloaded") or the watch cache ("Caching"). */
    isDownload: Boolean = false,
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
    /** A running sleep timer as the menu reads it ("25 minutes left"), or null for none. */
    sleepTimer: String? = null,
    /** The A-B repeat as marked so far, drawn on the timebar and named above it; null for none. */
    loop: AbLoop? = null,
    onHoverControls: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onEpisode: (MediaItem) -> Unit,
    onToggleRemaining: () -> Unit,
    onVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onMiniPlayer: () -> Unit,
    onOpenMenu: (MenuAt) -> Unit,
    onCloseMenu: () -> Unit,
    onMenuAction: (MenuAction) -> Unit,
    /** Opens the episode list; null (a film, or before the chat has answered) leaves the button out. */
    onEpisodes: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    // The right click menu hangs at the cursor whether or not the controls are up.
    if (menu?.anchor == MenuAt.Anchor.Cursor) {
        Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }.size(1.dp)) {
            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, onOpenMenu, onCloseMenu, onMenuAction)
        }
    }

    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
        Box(Modifier.fillMaxSize()) {
            // The 30 % tint over the whole picture, so white glyphs read on a bright frame.
            Box(Modifier.matchParentSize().background(Color(0x4D000000)))

            // Top bar.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(TopScrim)
                    .hoverReport(onHoverControls)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(PlayerIcons.ArrowBack, s.playerBackHint, onBack)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    OverlayButton(PlayerIcons.MoreVert, s.commonMore, onClick = { onOpenMenu(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) })
                    if (menu?.anchor == MenuAt.Anchor.Overflow) {
                        PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, onOpenMenu, onCloseMenu, onMenuAction)
                    }
                }
            }

            // The download chip, top right under the bar.
            if (downloaded != null && downloaded < 1f) {
                val percent = s.messages.formatter.percent(downloaded.toDouble())
                Chip(
                    if (isDownload) s.playerDownloadedChip(percent) else s.playerCachedChip(percent),
                    Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp),
                )
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
                    OverlayButton(PlayerIcons.SkipPrevious, s.playerPreviousEpisodeHint(s.playerPreviousUp(episodes.labelFor(previous))), { onEpisode(previous) }, size = 56, tonal = true)
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(PlayerTonalButton).clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (status.buffering) {
                        CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(if (status.playing) PlayerIcons.Pause else PlayerIcons.Play, if (status.playing) s.playerPause else s.playerPlay, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
                if (next != null) {
                    OverlayButton(PlayerIcons.SkipNext, s.playerNextEpisodeHint(s.playerNextUp(episodes.labelFor(next))), { onEpisode(next) }, size = 56, tonal = true)
                }
            }

            // Bottom: times, bar, button row.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(BottomScrim)
                    .hoverReport(onHoverControls)
                    .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(SeekMath.clock(status.positionMs), color = Color.White, fontSize = 13.sp)
                    // The chapter playing, as YouTube names it beside the time.
                    val chapter = Chapters.at(status.chapters, status.positionMs)
                    if (chapter >= 0) {
                        Text(
                            "  ·  " + Chapters.label(status.chapters, chapter),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (loop != null) {
                        Chip(
                            loop.endMs?.let { s.playerLoopChip(SeekMath.clock(loop.startMs), SeekMath.clock(it)) }
                                ?: s.playerLoopStartChip(SeekMath.clock(loop.startMs)),
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        if (showRemaining) SeekMath.remaining(status.positionMs, status.durationMs) else SeekMath.clock(status.durationMs),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleRemaining).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                TimeBar(status.positionMs, status.durationMs, status.bufferedMs, onSeekTo, status.chapters, loop)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OverlayButton(PlayerIcons.Subtitles, s.playerSubtitlesHint, onClick = { onOpenMenu(MenuAt(MenuPage.Subtitles, MenuAt.Anchor.Subtitles)) })
                        if (menu?.anchor == MenuAt.Anchor.Subtitles) {
                            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, onOpenMenu, onCloseMenu, onMenuAction)
                        }
                    }
                    Box {
                        OverlayButton(PlayerIcons.Audio, s.playerAudioHint, onClick = { onOpenMenu(MenuAt(MenuPage.Audio, MenuAt.Anchor.Audio)) })
                        if (menu?.anchor == MenuAt.Anchor.Audio) {
                            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, onOpenMenu, onCloseMenu, onMenuAction)
                        }
                    }
                    Box {
                        Tip(s.playerSpeedHint) {
                            Box(
                                Modifier.height(36.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(18.dp))
                                    .clickable { onOpenMenu(MenuAt(MenuPage.Speed, MenuAt.Anchor.Speed)) }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(SeekMath.speedLabel(status.speed), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        }
                        if (menu?.anchor == MenuAt.Anchor.Speed) {
                            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, onOpenMenu, onCloseMenu, onMenuAction)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    VolumeControl(status.volume, status.muted, onVolume, onToggleMute)
                    OverlayButton(PlayerIcons.PictureInPicture, s.playerMiniHint, onMiniPlayer)
                    OverlayButton(
                        if (fullscreen) PlayerIcons.FullscreenExit else PlayerIcons.Fullscreen,
                        if (fullscreen) s.playerExitFullscreenHint else s.playerFullscreenHint,
                        onToggleFullscreen,
                    )
                    // The bar's right end, where a streaming player keeps its episode list.
                    if (onEpisodes != null) EpisodesButton(onEpisodes)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.hoverReport(onHover: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onHover(true) }
    .onPointerEvent(PointerEventType.Exit) { onHover(false) }

/**
 * What to call [item] as a step from the episode playing: "S01E05  ·  The Lighthouse" as the
 * series view numbers it, else what its own name says, else its title.
 */
internal fun Episodes.labelFor(item: MediaItem): String = when (item.id) {
    next?.id -> nextTag?.label
    previous?.id -> previousTag?.label
    else -> null
} ?: EpisodeNeighbours.tagOf(item)?.label ?: item.title

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
internal fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 40, tonal: Boolean = false) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // A centre button sits on the tonal disc whether or not the pointer is on it, so it does not
    // vanish over a light frame; the bars' small buttons sit on their scrim and light up on hover.
    val rest = if (tonal) PlayerTonalButton else Color.Transparent
    Tip(label) {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(rest)
                .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
                .hoverable(hover)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(PlayerTonal).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = OnPlayerTonal, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeControl(volume: Int, muted: Boolean, onVolume: (Int) -> Unit, onToggleMute: () -> Unit) {
    val s = LocalStrings.current
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
        OverlayButton(icon, if (muted) s.playerUnmuteHint else s.playerMuteHint, onToggleMute)
    }
}

/**
 * The timebar: the played run, the buffered run ahead of it, and on hover the time under the
 * cursor (B2.3). A press or drag moves a preview; the seek happens on release, so dragging across
 * an hour does not send mpv sixty seeks.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TimeBar(
    position: Long,
    duration: Long,
    buffered: Long,
    onSeekTo: (Long) -> Unit,
    chapters: List<Chapter> = emptyList(),
    loop: AbLoop? = null,
) {
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
            // Chapter marks as gaps in the bar, the way YouTube splits it.
            val gap = 2.dp.toPx()
            for (chapter in chapters) {
                if (chapter.startMs <= 0) continue
                val x = size.width * SeekMath.fraction(chapter.startMs, duration)
                drawRect(Color(0xCC000000), Offset(x - gap / 2, 0f), Size(gap, size.height))
            }
            // The A-B loop: the run between the marks in amber, each mark a taller tick.
            if (loop != null && duration > 0) {
                val a = size.width * SeekMath.fraction(loop.startMs, duration)
                val b = loop.endMs?.let { size.width * SeekMath.fraction(it, duration) }
                if (b != null) drawRect(LoopColor.copy(alpha = 0.6f), Offset(a, 0f), Size(b - a, size.height))
                val reach = 5.dp.toPx()
                for (x in listOfNotNull(a, b)) {
                    drawRect(LoopColor, Offset(x - gap / 2, -reach), Size(gap, size.height + reach * 2))
                }
            }
            val thumb = if (thick) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(primary, thumb, Offset(size.width * played, size.height / 2))
        }
        val labelX = dragX ?: hoverX
        if (labelX != null && duration > 0) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { IntOffset(labelX.roundToInt() - 40.dp.roundToPx(), -30.dp.roundToPx()) }
                    .width(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Chip(SeekMath.clock(SeekMath.timeAt(labelX, width, duration)))
            }
        }
    }
}

/** One menu for the right click, the overflow button and the two track buttons, paged. */
@Composable
internal fun PlayerMenu(
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
    sleepTimer: String?,
    onOpenMenu: (MenuAt) -> Unit,
    onClose: () -> Unit,
    onAction: (MenuAction) -> Unit,
) {
    val s = LocalStrings.current
    fun pick(action: MenuAction) {
        onClose()
        onAction(action)
    }
    fun page(to: MenuPage) = onOpenMenu(menu.copy(page = to))

    @Composable
    fun Entry(text: String, checked: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
        DropdownMenuItem(
            // Two lines rather than an ellipsis: Material caps a menu item at 280 dp, which a
            // Malayalam or German line outgrows, and shrinking the type would go under the floor.
            text = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            onClick = onClick,
            leadingIcon = { Box(Modifier.size(18.dp)) { if (checked) Icon(PlayerIcons.Check, null, Modifier.size(18.dp)) } },
            trailingIcon = trailing?.let { { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) } },
        )
    }

    /**
     * A figure with a pair of buttons either side of it, for the settings a viewer nudges and
     * watches the result of: the menu stays open, so the next press is one click away.
     */
    @Composable
    fun Nudge(text: String, value: String, less: String, more: String, onLess: (() -> Unit)?, onMore: (() -> Unit)?) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lines up with the entries' text, past the space their tick takes.
            Spacer(Modifier.width(30.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onLess?.invoke() }, enabled = onLess != null) { Text(less) }
            Text(value, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
            TextButton(onClick = { onMore?.invoke() }, enabled = onMore != null) { Text(more) }
        }
    }

    TmDropdownMenu(expanded = true, onDismissRequest = onClose, modifier = Modifier.widthIn(min = 240.dp, max = 420.dp)) {
        val parent = menu.parentPage()
        if (parent != null) {
            Entry(s.commonBack) { page(parent) }
            HorizontalDivider()
        }
        when (menu.page) {
            MenuPage.Main -> {
                if (alwaysOnTopAvailable) Entry(s.playerAlwaysOnTop, trailing = s.playerKeyCtrlT) { pick(MenuAction.Do(PlayerAction.AlwaysOnTop)) }
                Entry(s.playerPlaybackOptions) { page(MenuPage.Options) }
                Entry(s.playerSubtitleStyleAndTiming) { page(MenuPage.SubtitleStyle) }
                if (fromTelegram || watchedLabel != null) HorizontalDivider()
                if (fromTelegram) {
                    Entry(s.commonCopyLink) { pick(MenuAction.CopyLink) }
                    if (savable) {
                        Entry(s.downloadsSave) { pick(MenuAction.Download) }
                        Entry(s.commonOpenElsewhere) { pick(MenuAction.OpenElsewhere) }
                    }
                }
                if (watchedLabel != null) Entry(watchedLabel) { pick(MenuAction.ToggleWatched) }
                HorizontalDivider()
                Entry(s.playerHelp, trailing = "?") { pick(MenuAction.Shortcuts) }
            }
            MenuPage.Options -> {
                Entry(s.playerDownmix, checked = status.downmix) { pick(MenuAction.ToggleDownmix) }
                Entry(s.playerVolumeBoost, checked = status.volumeBoost) { pick(MenuAction.ToggleVolumeBoost) }
                Entry(s.playerSleepTimer, trailing = sleepTimer ?: s.commonOff) { page(MenuPage.Sleep) }
                // The bar's button for this went, and it never had a key: this page is the way in.
                Entry(s.playerShape, trailing = status.scale.label) { page(MenuPage.Shape) }
                Entry(s.playerIgnoreClicks, checked = ignoreClicks) { pick(MenuAction.ToggleIgnoreClicks) }
                Entry(s.playerDetails, trailing = "I") { pick(MenuAction.Details) }
            }
            MenuPage.Audio -> {
                val list = tracks.filter { it.type == TrackType.Audio }
                if (list.isEmpty()) Entry(s.playerNoAudioTracks) { onClose() }
                list.forEach { t -> Entry(t.label, checked = t.selected) { pick(MenuAction.Track(TrackType.Audio, t)) } }
                HorizontalDivider()
                Nudge(s.playerDelay, SyncDelays.label(status.audioDelayMs), s.playerEarlier, s.playerLater,
                    { onAction(MenuAction.AudioDelay(-1)) }, { onAction(MenuAction.AudioDelay(1)) })
                if (status.audioDelayMs != 0L) Entry(s.playerResetDelay) { onAction(MenuAction.AudioDelay(0)) }
            }
            MenuPage.Subtitles -> {
                val list = tracks.filter { it.type == TrackType.Subtitle }
                Entry(s.commonOff, checked = list.none { it.selected }) { pick(MenuAction.Track(TrackType.Subtitle, null)) }
                list.forEach { t -> Entry(t.label, checked = t.selected) { pick(MenuAction.Track(TrackType.Subtitle, t)) } }
                if (com.tmplayer.online.OnlineSubtitles.available) Entry(s.onlineSearchOnline) { pick(MenuAction.SearchOnline) }
                HorizontalDivider()
                Entry(s.playerSubtitleStyleAndTiming) { page(MenuPage.SubtitleStyle) }
            }
            MenuPage.SubtitleStyle -> {
                Nudge(s.playerDelay, SyncDelays.label(status.subtitleDelayMs), s.playerEarlier, s.playerLater,
                    { onAction(MenuAction.SubtitleDelay(-1)) }, { onAction(MenuAction.SubtitleDelay(1)) })
                if (status.subtitleDelayMs != 0L) Entry(s.playerResetDelay) { onAction(MenuAction.SubtitleDelay(0)) }
                HorizontalDivider()
                // The look, previewed on the picture as it changes; the ends of each scale stop.
                val style = status.subtitleStyle
                val sizes = SubtitleSize.entries
                val places = SubtitlePosition.entries
                fun look(to: SubtitleStyle) = onAction(MenuAction.SubtitleLook(to))
                Nudge(s.subtitlesSize, style.size.label, s.subtitlesSmaller, s.subtitlesLarger,
                    sizes.getOrNull(style.size.ordinal - 1)?.let { size -> { look(style.copy(size = size)) } },
                    sizes.getOrNull(style.size.ordinal + 1)?.let { size -> { look(style.copy(size = size)) } })
                Nudge(s.subtitlesPosition, style.position.label, s.subtitlesLower, s.subtitlesHigher,
                    places.getOrNull(style.position.ordinal - 1)?.let { p -> { look(style.copy(position = p)) } },
                    places.getOrNull(style.position.ordinal + 1)?.let { p -> { look(style.copy(position = p)) } })
                Entry(s.subtitlesBox, checked = style.box) { look(style.copy(box = !style.box)) }
            }
            MenuPage.Sleep -> {
                // Only here, never on the picture: the countdown stays off the screen (1.16.0).
                if (sleepTimer != null) {
                    Entry(L.playerSleepTurnOff, trailing = sleepTimer) { pick(MenuAction.Sleep(null)) }
                    HorizontalDivider()
                }
                SleepTimer.CHOICES.forEach { minutes ->
                    Entry(SleepTimer.label(minutes)) { pick(MenuAction.Sleep(minutes)) }
                }
            }
            MenuPage.Speed -> PlaybackSpeed.CHOICES.forEach { speed ->
                Entry(PlaybackSpeed.label(speed), checked = kotlin.math.abs(speed - status.speed) < 0.001f) { pick(MenuAction.Speed(speed)) }
            }
            MenuPage.Shape -> VideoScale.entries.forEach { shape ->
                Entry(shape.label, checked = shape == status.scale) { pick(MenuAction.Shape(shape)) }
            }
        }
    }
}

/** The flashes of A4.1 and A4.2, the volume HUD, and the spinner for a stall with the controls down. */
@Composable
internal fun BoxScope.FeedbackLayer(flash: Flash?, spinner: Boolean, chip: String?) {
    val s = LocalStrings.current
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
            layer.align(Alignment.Center).size(72.dp).clip(CircleShape).background(PlayerTonalButton),
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
                // On a tonal pill: the side wash is pale, so bare white over a bright frame was
                // white on white.
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(PlayerTonal).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (back) "<<<" else ">>>", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(f.text, color = OnPlayerTonal, fontSize = 15.sp)
                }
            }
        }
        Flash.Kind.Volume -> Box(
            layer.align(Alignment.TopCenter).padding(top = 80.dp).clip(CircleShape).background(PlayerTonal)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (f.text == s.playerMuted || f.text == s.messages.formatter.percent(0.0)) PlayerIcons.VolumeOff else PlayerIcons.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(f.text, color = Color.White, fontSize = 15.sp)
            }
        }
        Flash.Kind.Text -> Box(
            layer.align(Alignment.TopCenter).padding(top = 80.dp).clip(CircleShape).background(PlayerTonal)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(f.text, color = Color.White, fontSize = 15.sp) }
    }
}

/** "Resuming from 1:02:14" with Start over beside it (A4.8), for eight seconds. */
@Composable
internal fun BoxScope.ResumeNotice(from: Long, lifted: Boolean, onStartOver: () -> Unit, onTimeout: () -> Unit) {
    val s = LocalStrings.current
    LaunchedEffect(from) {
        delay(8_000)
        onTimeout()
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (lifted) 120.dp else 24.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color(0xCC1C1C1E)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.playerResumingFrom(SeekMath.clock(from)), color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onStartOver) { Text(s.playerStartOver) }
    }
}

/**
 * A4.7: the next episode, offered in the last half minute. The only next-episode prompt while the
 * picture is still playing: the countdown in [StatusSheet] shows only when the video has ended
 * without this card having been up (a seek to the very end, or no known length), see the end
 * handler in PlayerScreen.
 */
@Composable
internal fun BoxScope.NextUpCard(label: String, secondsLeft: Int, lifted: Boolean, onPlayNow: () -> Unit, onHide: () -> Unit) {
    val s = LocalStrings.current
    AnimatedVisibility(
        true,
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (lifted) 120.dp else 24.dp),
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xE61C1C1E), modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(s.playerNextUp(label), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.playerNextIn(secondsLeft), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onPlayNow) { Text(s.playerPlayNow) }
                    TextButton(onClick = onHide) { Text(s.commonHide) }
                }
            }
        }
    }
}

/** "Episodes" with its glyph, a pill on the bar: words, since a stack of frames alone says little. */
@Composable
private fun EpisodesButton(onClick: () -> Unit) {
    val s = LocalStrings.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.padding(start = 4.dp).height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(com.tmplayer.ui.components.TmIcons.Episodes, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(s.episodesTitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The episode list over the dimmed picture: [com.tmplayer.ui.player.EpisodesPanel] in the player's
 * dialog surface. A click on the dim or Esc (the player's key handler) closes it; the arrow keys
 * walk it, as Tab does, with the episode playing focused first.
 */
@Composable
internal fun BoxScope.EpisodesSheet(
    state: com.tmplayer.ui.player.EpisodesState,
    watch: com.tmplayer.ui.browse.SeriesWatch,
    actions: com.tmplayer.ui.player.EpisodesActions,
    position: () -> Long,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    BoxWithConstraints(
        Modifier.matchParentSize().background(FloatingTone.scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = Floating.DialogShape,
            color = FloatingTone.dialog,
            border = floatingBorder(),
            modifier = Modifier
                .widthIn(max = 1040.dp)
                .heightIn(max = maxHeight - 32.dp)
                .padding(16.dp)
                // A click inside the panel is the panel's, not the dim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                        Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                        Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
                        Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
                        else -> return@onPreviewKeyEvent false
                    }
                    focus.moveFocus(direction)
                    true
                },
        ) {
            com.tmplayer.ui.player.EpisodesPanel(
                state = state,
                watch = watch,
                actions = actions,
                position = position,
                modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                focusCurrent = true,
                trailing = { OverlayButton(PlayerIcons.Close, s.commonClose, onClose, size = 36) },
            )
        }
    }
}

/**
 * "Skip intro", at the bottom end over the picture while a show's marked intro runs (see
 * [com.tmplayer.data.IntroSkip]). A click or Enter jumps to its end.
 */
@Composable
internal fun BoxScope.SkipIntroPill(lifted: Boolean, onSkip: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = if (lifted) 120.dp else 32.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE61C1C1E))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .clickable(onClick = onSkip)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PlayerIcons.SkipNext, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(s.episodesSkipIntro, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** B3.4 item 4: what a tester's report needs, refreshed while open. */
@Composable
internal fun BoxScope.DetailsPanel(rows: () -> List<Pair<String, String>>, onClose: () -> Unit) {
    val s = LocalStrings.current
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
                Text(s.playerDetails, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OverlayButton(PlayerIcons.Close, s.playerCloseDetailsHint, onClose, size = 32)
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

/** The loading, error, countdown, "Still watching?" and finished sheets over the picture. */
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
    onKeepWatching: () -> Unit = {},
    /** What to call the episode a countdown or "Still watching?" offers: see [labelFor]. */
    nextLabel: (MediaItem) -> String = { Episodes().labelFor(it) },
) {
    val s = LocalStrings.current
    if (phase == Phase.Playing || phase is Phase.Loading) return
    Box(Modifier.matchParentSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            when (phase) {
                // The pre-roll has a screen of its own: see LoaderSheet.
                is Phase.Loading -> Unit
                is Phase.Failed -> {
                    Text(phase.message, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = onRetry) { Text(s.commonTryAgain) }
                        TextButton(onClick = onBack) { Text(s.commonBack) }
                    }
                }
                is Phase.Countdown -> {
                    Text(s.playerNextUp(nextLabel(phase.next)), color = Color.White, fontSize = 17.sp)
                    Text(s.playerStartingIn(phase.seconds), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = { onPlayNext(phase.next) }) { Text(s.playerPlayNow) }
                        TextButton(onClick = onCancelNext) { Text(s.commonCancel) }
                    }
                }
                Phase.Finished -> {
                    Text(s.playerTheEnd, color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = onWatchAgain) { Text(s.playerWatchAgain) }
                        TextButton(onClick = onBack) { Text(s.commonBack) }
                    }
                }
                is Phase.StillWatching -> {
                    Text(s.playerStillWatching, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(phase.why, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center)
                    val code = phase.next?.let(nextLabel)
                    Text(
                        code?.let { s.playerNextUp(it) } ?: s.playerPausedEverything,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    val keep = remember { FocusRequester() }
                    Row {
                        Button(onClick = onKeepWatching, modifier = Modifier.focusRequester(keep)) { Text(s.playerKeepWatching) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onBack) { Text(s.commonBack) }
                    }
                    // Enter answers it from the keyboard, as the remote's OK does on TV.
                    LaunchedEffect(phase) { runCatching { keep.requestFocus() } }
                }
                Phase.Playing -> Unit
            }
        }
    }
}

/**
 * The "?" sheet. Two columns on a window wide enough for them, so the whole table fits a 720p
 * window without scrolling; one column, scrolling, on a narrow one.
 */
@Composable
internal fun BoxScope.ShortcutSheet(onClose: () -> Unit, mac: Boolean = false, wheelSeeks: Boolean = false) {
    val s = LocalStrings.current
    BoxWithConstraints(
        Modifier.matchParentSize().background(FloatingTone.scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val groups = PlayerKeys.sheetGroups(mac, wheelSeeks)
        val twoColumns = maxWidth >= 900.dp
        // A dialog like any other, so it has the edge and corner the television's key sheet has.
        Surface(
            shape = Floating.DialogShape,
            color = FloatingTone.dialog,
            border = floatingBorder(),
            modifier = Modifier.widthIn(max = if (twoColumns) 1040.dp else 560.dp).padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.playerShortcuts, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OverlayButton(PlayerIcons.Close, s.commonClose, onClose, size = 32)
                }
                Spacer(Modifier.height(12.dp))
                @Composable
                fun Group(heading: String, part: List<Pair<String, String>>) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        Text(heading, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                        part.forEach { (what, keys) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(what, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
                                Text(keys, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }
                    }
                }
                if (twoColumns) {
                    // The first two groups down the left, the window and the rarely used down the right.
                    Row {
                        Column(Modifier.weight(1f)) { groups.take(2).forEach { (name, part) -> Group(name, part) } }
                        Spacer(Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) { groups.drop(2).forEach { (name, part) -> Group(name, part) } }
                    }
                } else {
                    groups.forEach { (name, part) -> Group(name, part) }
                }
            }
        }
    }
}
