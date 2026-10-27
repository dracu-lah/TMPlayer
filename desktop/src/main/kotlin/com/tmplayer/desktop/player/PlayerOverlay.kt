package com.tmplayer.desktop.player

import com.tmplayer.ui.browse.copyHeading
import com.tmplayer.ui.browse.copyDetail
import com.tmplayer.data.copyQuality
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.CopyHandoff
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import com.tmplayer.desktop.ui.WindowControls
import com.tmplayer.desktop.ui.windowDrag
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
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
import androidx.compose.ui.focus.focusProperties
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
import com.tmplayer.data.Bookmark
import com.tmplayer.data.MediaItem
import com.tmplayer.online.SkipKind
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.Stereo3d
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
import com.tmplayer.ui.components.tipFadeIn

/** What a menu entry asks for, beyond the plain [PlayerAction]s. */
/**
 * The copies of the video playing ([copies], itself among them) and which is playing, for the
 * menu's Copies page. Handed down as a local: the menu is opened from five places in the overlay.
 */
internal data class PlayerCopies(
    val copies: List<MediaItem> = emptyList(),
    val playing: String = "",
    /** Telegram's lighter copies of the upload playing (CP80), and the one playing (0 the upload). */
    val qualities: List<com.tmplayer.data.TelegramQuality> = emptyList(),
    val playingQuality: Int = 0,
)

internal val LocalPlayerCopies = androidx.compose.runtime.staticCompositionLocalOf { PlayerCopies() }

internal sealed interface MenuAction {
    data class Do(val action: PlayerAction) : MenuAction

    /** Another copy of the video, played from the same moment. */
    data class SwitchCopy(val copy: MediaItem) : MenuAction

    /** Telegram's copy of that height in place of the upload, or the upload for 0 (CP80). */
    data class SwitchQuality(val height: Int) : MenuAction
    data class Track(val type: TrackType, val track: MediaTrack?) : MenuAction
    data class Speed(val speed: Float) : MenuAction
    data class Shape(val scale: VideoScale) : MenuAction
    data object ToggleDownmix : MenuAction
    data object ToggleVolumeBoost : MenuAction
    data object ToggleNightMode : MenuAction

    /** A new 3D video choice, applied with the page left open for the next. */
    data class Stereo(val stereo: Stereo3d) : MenuAction

    /** Minutes from now, [SleepTimer.END_OF_VIDEO], or null to turn the timer off. */
    data class Sleep(val minutes: Int?) : MenuAction

    /** One step later (1) or earlier (-1), or 0 for back in step. The menu stays open for more. */
    data class SubtitleDelay(val direction: Int) : MenuAction
    data class AudioDelay(val direction: Int) : MenuAction

    /** A new subtitle look, previewed on the picture while the menu stays open. */
    data class SubtitleLook(val style: SubtitleStyle) : MenuAction
    data object StartOver : MenuAction

    /** Locks the player against the mouse, the touchpad, the touch screen and the keyboard. */
    data object LockScreen : MenuAction
    data object ToggleIgnoreClicks : MenuAction
    data object CopyLink : MenuAction
    data object Download : MenuAction
    data object OpenElsewhere : MenuAction
    data object Details : MenuAction
    data object Shortcuts : MenuAction
    data object ToggleWatched : MenuAction

    /** The subtitle menu's "Search online", in a build with the OpenSubtitles key. */
    data object SearchOnline : MenuAction

    /** MSone's search, on a device where it was switched on by hand while MSone is being set up. */
    data object SearchMsone : MenuAction

    /** A subtitle file from the disk, through the system's file dialog; dropping one does the same. */
    data object LoadSubtitleFile : MenuAction

    /** The moment playing as a bookmark; the player asks for its note. */
    data object AddBookmark : MenuAction
    data class JumpTo(val bookmark: Bookmark) : MenuAction
    data class EditBookmark(val bookmark: Bookmark) : MenuAction
    data class DeleteBookmark(val bookmark: Bookmark) : MenuAction
}

/**
 * The scrims behind the top bar and the bottom cluster, the Android player's
 * bg_controls_scrim_top and bg_controls_scrim_bottom: 160 dp from 70 per cent black at the top
 * edge, 260 dp to 80 per cent at the bottom. Five stops on an eased curve rather than a straight
 * ramp, so the fade has no line to see, and the band the text sits in (the title, the times) still
 * holds 60 per cent or more, where white text keeps AA contrast on a white frame.
 */
private val TopScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.70f),
    0.25f to Color.Black.copy(alpha = 0.64f),
    0.5f to Color.Black.copy(alpha = 0.45f),
    0.75f to Color.Black.copy(alpha = 0.18f),
    1f to Color.Transparent,
)
private val BottomScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.25f to Color.Black.copy(alpha = 0.28f),
    0.5f to Color.Black.copy(alpha = 0.60f),
    0.75f to Color.Black.copy(alpha = 0.74f),
    1f to Color.Black.copy(alpha = 0.80f),
)
private val TopScrimHeight = 160.dp
private val BottomScrimHeight = 260.dp

/**
 * The seat the bar's buttons share: one pill in black at half strength with a faint white edge,
 * so the row reads as one control over any frame instead of glyphs floating on the picture.
 */
private val ControlsPillFill = Color.Black.copy(alpha = 0.5f)
private val ControlsPillEdge = Color.White.copy(alpha = 0.16f)

/** The times and the line under the title: white at 72 per cent, as text on art is everywhere. */
private val OnArtMuted = Color.White.copy(alpha = 0.72f)

/** How the controls arrive and leave: in a touch slower than out, the way Nuvio's do. */
private const val FADE_IN_MS = 250
private const val FADE_OUT_MS = 200

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

/** A bookmark's tick on the timebar: near white, so it reads over the played run and the rest alike. */
private val BookmarkColor = Color(0xE6FFFFFF)

/**
 * The picture itself as a click target (B2.3): a single click plays or pauses 300 ms after the
 * button comes up, so a double click (fullscreen) does not flicker the picture first; holding the
 * left button down past [HOLD_MS] plays fast until it is let go ([onHold], the phone's "Hold to
 * speed up"), and is no click; right click opens the menu at the cursor; the mouse's back button
 * goes back.
 */
@Composable
internal fun BoxScope.VideoGestures(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: (Offset) -> Unit,
    onMouseBack: () -> Unit,
    scope: CoroutineScope,
    /** True as a hold starts, false as it ends. */
    onHold: (Boolean) -> Unit = {},
) {
    val click by rememberUpdatedState(onClick)
    val double by rememberUpdatedState(onDoubleClick)
    val right by rememberUpdatedState(onRightClick)
    val back by rememberUpdatedState(onMouseBack)
    val hold by rememberUpdatedState(onHold)
    var pending by remember { mutableStateOf<Job?>(null) }
    Box(
        Modifier.matchParentSize().testTag("video").pointerInput(Unit) {
            awaitPointerEventScope {
                // The left press under way: its hold timer, whether the hold began, and whether it
                // was a double click's second press, which leaves nothing for its release to do.
                var holdTimer: Job? = null
                var holding = false
                var doubled = false
                var down = false
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Press -> {
                            val at = event.changes.firstOrNull()?.position ?: Offset.Zero
                            when {
                                event.buttons.isSecondaryPressed -> right(at)
                                event.buttons.isBackPressed -> back()
                                event.buttons.isPrimaryPressed && !down -> {
                                    down = true
                                    val waiting = pending
                                    if (waiting != null && waiting.isActive) {
                                        waiting.cancel()
                                        pending = null
                                        doubled = true
                                        double()
                                    } else {
                                        holdTimer = scope.launch {
                                            delay(HOLD_MS)
                                            holding = true
                                            hold(true)
                                        }
                                    }
                                }
                            }
                        }
                        PointerEventType.Release -> if (down && !event.buttons.isPrimaryPressed) {
                            down = false
                            holdTimer?.cancel()
                            holdTimer = null
                            when {
                                doubled -> doubled = false
                                holding -> {
                                    holding = false
                                    hold(false)
                                }
                                else -> pending = scope.launch {
                                    delay(DOUBLE_CLICK_MS)
                                    pending = null
                                    click()
                                }
                            }
                        }
                        else -> continue
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        },
    )
}

private const val DOUBLE_CLICK_MS = 300L

/** How long the left button stays down before a press is a hold rather than a click. */
private const val HOLD_MS = 500L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.PlayerOverlay(
    visible: Boolean,
    status: PlaybackStatus,
    /**
     * The live position and the buffer's end, read only by the time row and the bar, so the clock
     * recomposes those and not the overlay; [status]'s own pair is the fixtures' default.
     */
    positionMs: () -> Long = { status.positionMs },
    bufferedMs: () -> Long = { status.bufferedMs },
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
    /** This video's bookmarks for the menu and the timebar; null where the video can have none. */
    bookmarks: List<Bookmark>? = null,
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
    /** The Cast button and its device popover (CP73); null leaves it out. */
    castButton: (@Composable () -> Unit)? = null,
    /** The Subtitles button; false while a cast goes without subtitles ([com.tmplayer.cast.CAST_SUBTITLES]). */
    subtitlesButton: Boolean = true,
) {
    val s = LocalStrings.current
    // The right click menu hangs at the cursor whether or not the controls are up.
    if (menu?.anchor == MenuAt.Anchor.Cursor) {
        Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }.size(1.dp)) {
            PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, bookmarks, loop, onOpenMenu, onCloseMenu, onMenuAction)
        }
    }

    AnimatedVisibility(
        visible,
        Modifier.matchParentSize(),
        enter = fadeIn(tween(FADE_IN_MS, easing = Motion.Standard)),
        exit = fadeOut(tween(FADE_OUT_MS, easing = Motion.Standard)),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The 30 % tint over the whole picture, so white glyphs read on a bright frame.
            Box(Modifier.matchParentSize().background(Color(0x4D000000)))
            // The scrims, each a fixed reach from its edge whatever the bars hold.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TopScrimHeight).background(TopScrim))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BottomScrimHeight).background(BottomScrim))

            // Top bar.
            // Windowed, the bar is the window's title bar too: its empty space moves the window, and
            // the window's own buttons end it (where the app draws them), fading out with the rest.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .hoverReport(onHoverControls)
                    .then(if (fullscreen) Modifier else Modifier.windowDrag())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(TmIcons.ArrowBack, s.playerBackHint, onBack)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = OnArtMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    OverlayButton(TmIcons.MoreVert, s.commonMore, onClick = { onOpenMenu(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) })
                    if (menu?.anchor == MenuAt.Anchor.Overflow) {
                        PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, bookmarks, loop, onOpenMenu, onCloseMenu, onMenuAction)
                    }
                }
                if (!fullscreen) WindowControls(onArt = true)
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
                    OverlayButton(TmIcons.SkipPrevious, s.playerPreviousEpisodeHint(s.playerPreviousUp(episodes.labelFor(previous))), { onEpisode(previous) }, size = 56, tonal = true)
                }
                PlayPauseButton(status, onTogglePlay)
                if (next != null) {
                    OverlayButton(TmIcons.SkipNext, s.playerNextEpisodeHint(s.playerNextUp(episodes.labelFor(next))), { onEpisode(next) }, size = 56, tonal = true)
                }
            }

            // Bottom: times, bar, button row.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .hoverReport(onHoverControls)
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp),
            ) {
                TimesAndBar(positionMs, bufferedMs, status.durationMs, status.chapters, loop, bookmarks, showRemaining, onToggleRemaining, onSeekTo)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ControlsPill {
                        if (subtitlesButton) {
                            Box {
                                OverlayButton(TmIcons.Subtitles, s.playerSubtitlesHint, onClick = { onOpenMenu(MenuAt(MenuPage.Subtitles, MenuAt.Anchor.Subtitles)) })
                                if (menu?.anchor == MenuAt.Anchor.Subtitles) {
                                    PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, bookmarks, loop, onOpenMenu, onCloseMenu, onMenuAction)
                                }
                            }
                        }
                        Box {
                            OverlayButton(TmIcons.Audio, s.playerAudioHint, onClick = { onOpenMenu(MenuAt(MenuPage.Audio, MenuAt.Anchor.Audio)) })
                            if (menu?.anchor == MenuAt.Anchor.Audio) {
                                PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, bookmarks, loop, onOpenMenu, onCloseMenu, onMenuAction)
                            }
                        }
                        Box {
                            Tip(s.playerSpeedHint) {
                                Box(
                                    Modifier.height(40.dp).widthIn(min = 48.dp).clip(CircleShape)
                                        .clickable { onOpenMenu(MenuAt(MenuPage.Speed, MenuAt.Anchor.Speed)) }.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(SeekMath.speedLabel(status.speed), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                            }
                            if (menu?.anchor == MenuAt.Anchor.Speed) {
                                PlayerMenu(menu, status, tracks, fullscreen, ignoreClicks, miniPlayerAvailable, alwaysOnTopAvailable, fromTelegram, savable, watchedLabel, sleepTimer, bookmarks, loop, onOpenMenu, onCloseMenu, onMenuAction)
                            }
                        }
                        // A click steps the shape, as W does; the menu keeps its page of the three.
                        ShapeButton(status.scale) { onMenuAction(MenuAction.Do(PlayerAction.CycleShape)) }
                    }
                    Spacer(Modifier.weight(1f))
                    ControlsPill {
                        VolumeControl(status.volume, status.muted, onVolume, onToggleMute)
                        castButton?.invoke()
                        // Left out where there is no mini player to open (a browser without picture in picture).
                        if (miniPlayerAvailable) OverlayButton(TmIcons.PictureInPicture, s.playerMiniHint(commandKey(s)), onMiniPlayer)
                        OverlayButton(
                            if (fullscreen) TmIcons.FullscreenExit else TmIcons.Fullscreen,
                            if (fullscreen) s.playerExitFullscreenHint else s.playerFullscreenHint,
                            onToggleFullscreen,
                        )
                    }
                    // The bar's right end, where a streaming player keeps its episode list.
                    if (onEpisodes != null) EpisodesButton(onEpisodes)
                }
            }
        }
    }
}

/**
 * The bar's picture shape button: the glyph of the shape in force (the video inside the screen,
 * cropped, pulled to the edges), its name and key in the tooltip, and a small pop as the shape
 * changes, by click or by W, so the button answers before the shape's name flashes over the
 * picture. Reduce motion keeps the plain swap.
 */
@Composable
private fun ShapeButton(scale: VideoScale, onClick: () -> Unit) {
    val s = LocalStrings.current
    val pop = remember { Animatable(1f) }
    var drawn by remember { mutableStateOf<VideoScale?>(null) }
    LaunchedEffect(scale) {
        val before = drawn
        drawn = scale
        if (before == null || before == scale || Motion.reduced) return@LaunchedEffect
        pop.snapTo(0.82f)
        pop.animateTo(1f, tween(220, easing = Motion.Standard))
    }
    val icon = when (scale) {
        VideoScale.Fit -> TmIcons.ShapeFit
        VideoScale.Crop -> TmIcons.ShapeCrop
        VideoScale.Stretch -> TmIcons.ShapeStretch
    }
    Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        OverlayButton(icon, s.playerPictureShapeHint(scale.label), onClick)
    }
}

/** One pill holding a group of the bar's buttons, in [ControlsPillFill] with a [ControlsPillEdge]. */
@Composable
private fun ControlsPill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(ControlsPillFill)
            .border(1.dp, ControlsPillEdge, CircleShape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
            Surface(modifier = Modifier.tipFadeIn(), shape = RoundedCornerShape(6.dp), color = Color(0xE6202022)) {
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
internal fun Chip(text: String, modifier: Modifier = Modifier) {
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
                // Never the keyboard's: a slider that took focus on a click would keep Space and
                // Esc from the player until the video was clicked again.
                modifier = Modifier.width(110.dp).padding(end = 4.dp).focusProperties { canFocus = false },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
            )
        }
        val icon = when {
            muted || volume == 0 -> TmIcons.VolumeOff
            volume < 50 -> TmIcons.VolumeDown
            else -> TmIcons.VolumeUp
        }
        OverlayButton(icon, if (muted) s.playerUnmuteHint else s.playerMuteHint, onToggleMute)
    }
}

/**
 * The time, the chapter and the time left over the bar, and the bar: the only parts of the
 * controls that read the position, in a scope of their own so the clock recomposes just these.
 */
@Composable
private fun TimesAndBar(
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    durationMs: Long,
    chapters: List<Chapter>,
    loop: AbLoop?,
    bookmarks: List<Bookmark>?,
    showRemaining: Boolean,
    onToggleRemaining: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val s = LocalStrings.current
    val position = positionMs()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(SeekMath.clock(position), color = OnArtMuted, fontSize = 13.sp)
        // The chapter playing, as YouTube names it beside the time.
        val chapter = Chapters.at(chapters, position)
        if (chapter >= 0) {
            Text(
                "  ·  " + Chapters.label(chapters, chapter),
                color = OnArtMuted,
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
            if (showRemaining) SeekMath.remaining(position, durationMs) else SeekMath.clock(durationMs),
            color = OnArtMuted,
            fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleRemaining).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
    TimeBar(position, durationMs, bufferedMs(), onSeekTo, chapters, loop, bookmarks.orEmpty())
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
    bookmarks: List<Bookmark> = emptyList(),
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
        val track = Tone.track
        // 4 dp at rest, 8 dp under the pointer or a drag, grown over the quick motion step.
        val barHeight by animateDpAsState(if (thick) 8.dp else 4.dp, Motion.standard(Motion.Fast))
        Canvas(Modifier.fillMaxWidth().height(barHeight)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (ahead > played) drawRoundRect(primary.copy(alpha = 0.35f), size = Size(size.width * ahead, size.height), cornerRadius = r)
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
            // Bookmarks: a small light tick above the bar at each, out of the way of the loop's.
            if (duration > 0) {
                val tick = 3.dp.toPx()
                for (mark in bookmarks) {
                    val x = size.width * SeekMath.fraction(mark.positionMs, duration)
                    drawRect(BookmarkColor, Offset(x - gap / 2, -tick - 2.dp.toPx()), Size(gap, tick + 2.dp.toPx()))
                }
            }
            val thumb = if (thick) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(primary, thumb, Offset(size.width * played, size.height / 2))
        }
        val labelX = dragX ?: hoverX
        if (labelX != null && duration > 0) {
            val at = SeekMath.timeAt(labelX, width, duration)
            // The card for the moment under the pointer (Telegram's storyboard, CP80, else the
            // video's own frame), with its time in a chip. Placed from the bar's left edge in a
            // right to left layout too: the bar and the pointer's x run left to right there.
            SeekPreview(SeekMath.clock(at), at, labelX, width, Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft))
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
    /** This video's bookmarks, or null where it can have none (and the menu has no line for them). */
    bookmarks: List<Bookmark>? = null,
    /** The A-B repeat as marked so far, which names the menu's repeat line; null for none. */
    loop: AbLoop? = null,
    onOpenMenu: (MenuAt) -> Unit,
    onClose: () -> Unit,
    onAction: (MenuAction) -> Unit,
) {
    val s = LocalStrings.current
    val copies = LocalPlayerCopies.current
    fun pick(action: MenuAction) {
        onClose()
        onAction(action)
    }
    fun page(to: MenuPage) = onOpenMenu(menu.copy(page = to))

    @Composable
    fun Entry(text: String, checked: Boolean = false, trailing: String? = null, icon: ImageVector? = null, detail: String? = null, onClick: () -> Unit) {
        DropdownMenuItem(
            // Two lines rather than an ellipsis: Material caps a menu item at 280 dp, which a
            // Malayalam or German line outgrows, and shrinking the type would go under the floor.
            text = {
                if (detail == null) {
                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(detail, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            onClick = onClick,
            leadingIcon = {
                Box(Modifier.size(18.dp)) {
                    when {
                        checked -> Icon(TmIcons.Check, null, Modifier.size(18.dp))
                        icon != null -> Icon(icon, null, Modifier.size(18.dp))
                    }
                }
            },
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
                // First, under its own divider, with the lock the phone's and the television's
                // menus draw beside the same words (user, 2026-10-18).
                Entry(s.playerLockScreen, icon = TmIcons.Lock) { pick(MenuAction.LockScreen) }
                HorizontalDivider()
                // A video posted more than once: which copy is playing, and the way to another.
                if (copies.copies.size > 1 || copies.qualities.isNotEmpty()) {
                    val playing = copies.copies.firstOrNull { it.id == copies.playing }
                    val trailing = if (copies.playingQuality != 0) com.tmplayer.data.TelegramQualities.label(copies.playingQuality) else playing?.copyQuality()
                    Entry(s.playerCopies, trailing = trailing) { page(MenuPage.Copies) }
                }
                if (alwaysOnTopAvailable) Entry(s.playerAlwaysOnTop, trailing = s.playerKeyCommandT(commandKey(s))) { pick(MenuAction.Do(PlayerAction.AlwaysOnTop)) }
                Entry(s.playerPlaybackOptions) { page(MenuPage.Options) }
                // On the first page, as the phone's pinch is one move away: W cycles it too.
                Entry(s.playerPictureShape, trailing = status.scale.label) { page(MenuPage.Shape) }
                Entry(s.playerStereo, trailing = stereoSummary(status.stereo)) { page(MenuPage.Stereo) }
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
                if (bookmarks != null) {
                    HorizontalDivider()
                    Entry(s.playerAddBookmark, trailing = "B") { pick(MenuAction.AddBookmark) }
                    Entry(s.playerBookmarks, trailing = bookmarks.size.takeIf { it > 0 }?.let { s.messages.formatter.number(it) }) { page(MenuPage.Bookmarks) }
                }
                HorizontalDivider()
                Entry(s.playerHelp, trailing = "?") { pick(MenuAction.Shortcuts) }
            }
            MenuPage.Options -> {
                Entry(s.playerDownmix, checked = status.downmix) { pick(MenuAction.ToggleDownmix) }
                Entry(s.playerVolumeBoost, checked = status.volumeBoost) { pick(MenuAction.ToggleVolumeBoost) }
                Entry(s.playerNightMode, checked = status.nightMode) { pick(MenuAction.ToggleNightMode) }
                Entry(s.playerSleepTimer, trailing = sleepTimer ?: s.commonOff) { page(MenuPage.Sleep) }
                val repeat = when {
                    loop == null -> s.playerLoopSetStart
                    loop.endMs == null -> s.playerLoopSetEnd
                    else -> s.playerLoopClear
                }
                Entry(repeat, trailing = "R") { pick(MenuAction.Do(PlayerAction.AbRepeat)) }
                Entry(s.playerGoToTime, trailing = s.playerKeyCommandG(commandKey(s))) { pick(MenuAction.Do(PlayerAction.GoToTime)) }
                // A chaptered file only; the menu stays open for the next step, as the frame steps do.
                if (status.chapters.isNotEmpty()) {
                    Entry(s.playerChapterNext) { onAction(MenuAction.Do(PlayerAction.ChapterStep(forward = true))) }
                    Entry(s.playerChapterPrevious) { onAction(MenuAction.Do(PlayerAction.ChapterStep(forward = false))) }
                }
                // Paused only, as on the phone; the menu stays open for the next step.
                if (!status.playing) {
                    Entry(s.playerFrameNext, trailing = ".") { onAction(MenuAction.Do(PlayerAction.FrameStep(forward = true))) }
                    Entry(s.playerFramePrevious, trailing = ",") { onAction(MenuAction.Do(PlayerAction.FrameStep(forward = false))) }
                }
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
                if (com.tmplayer.online.OnlineSubtitles.current?.store?.now?.msoneShown == true) Entry(s.msoneSearch) { pick(MenuAction.SearchMsone) }
                Entry(s.playerLoadSubtitleFile) { pick(MenuAction.LoadSubtitleFile) }
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
                // The rest step through their choices a click at a time, the menu staying open on
                // the picture so each click shows on the subtitles behind it.
                Entry(s.subtitlesColour, trailing = style.colour.label) { look(style.nextColour()) }
                Entry(s.subtitlesBox, trailing = style.boxLevel.label) { look(style.nextBox()) }
                if (!style.box) Entry(s.subtitlesEdge, trailing = style.edge.label) { look(style.nextEdge()) }
                Entry(s.subtitlesFace, trailing = style.face.label) { look(style.nextFace()) }
                Entry(s.subtitlesWeightBold, checked = style.bold) { look(style.copy(bold = !style.bold)) }
                if (!style.isDefault) Entry(s.subtitlesDefaultLook, icon = TmIcons.Refresh) { look(SubtitleStyle()) }
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
            MenuPage.Copies -> {
                val spans = VideoCopies.spansChats(copies.copies)
                copies.copies.forEach { copy ->
                    val chat = if (spans) CopyHandoff.chatTitleOf(copy) else null
                    val here = copy.id == copies.playing
                    val shown = if (here) com.tmplayer.data.TelegramQualities.upload(copy) else copy
                    val line = listOfNotNull(
                        copyHeading(shown),
                        s.detailUploadQuality.takeIf { here && copies.qualities.isNotEmpty() },
                        copyDetail(shown, s.messages, chat),
                    ).filter { it.isNotBlank() }.joinToString("  ·  ")
                    val ticked = here && copies.playingQuality == 0
                    Entry(line, checked = ticked) {
                        when {
                            ticked -> onClose()
                            here -> pick(MenuAction.SwitchQuality(0))
                            else -> pick(MenuAction.SwitchCopy(copy))
                        }
                    }
                    // Telegram's own lighter copies of this upload, under it.
                    if (here) copies.qualities.forEach { q ->
                        val on = q.height == copies.playingQuality
                        val name = listOfNotNull(
                            s.detailTelegramQuality(com.tmplayer.data.TelegramQualities.label(q.height)),
                            q.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
                        ).joinToString("  ·  ")
                        Entry(name, checked = on) { if (on) onClose() else pick(MenuAction.SwitchQuality(q.height)) }
                    }
                }
            }
            MenuPage.Stereo -> {
                // Every pick applies at once and the page stays open: the picture behind it is
                // the answer to whether the layout and the glasses are right.
                val now = status.stereo
                fun choose(next: Stereo3d) = onAction(MenuAction.Stereo(next))
                StereoHeading(s.playerStereoLayout)
                Entry(s.commonOff, checked = !now.on, detail = s.playerStereoOffDetail) { choose(now.copy(layout = Stereo3d.Layout.Off)) }
                Entry(s.playerStereoSideBySide, checked = now.layout == Stereo3d.Layout.SideBySide, detail = s.playerStereoSideBySideDetail) {
                    choose(now.copy(layout = Stereo3d.Layout.SideBySide))
                }
                Entry(s.playerStereoTopBottom, checked = now.layout == Stereo3d.Layout.TopBottom, detail = s.playerStereoTopBottomDetail) {
                    choose(now.copy(layout = Stereo3d.Layout.TopBottom))
                }
                Entry(s.playerStereoRows, checked = now.layout == Stereo3d.Layout.RowInterleaved, detail = s.playerStereoRowsDetail) {
                    choose(now.copy(layout = Stereo3d.Layout.RowInterleaved))
                }
                Entry(s.playerStereoColumns, checked = now.layout == Stereo3d.Layout.ColumnInterleaved, detail = s.playerStereoColumnsDetail) {
                    choose(now.copy(layout = Stereo3d.Layout.ColumnInterleaved))
                }
                HorizontalDivider()
                StereoHeading(s.playerStereoGlasses)
                Stereo3d.View.entries.forEach { view ->
                    Entry(
                        stereoGlassesLabel(view),
                        checked = now.view == view,
                        detail = when (view) {
                            Stereo3d.View.OneEye -> s.playerStereoOneEyeDetail
                            Stereo3d.View.Vr -> s.playerStereoVrDetail
                            else -> null
                        },
                    ) { choose(now.copy(view = view)) }
                }
                HorizontalDivider()
                Entry(s.playerStereoSwap, checked = now.swapEyes, detail = s.playerStereoSwapDetail) { choose(now.copy(swapEyes = !now.swapEyes)) }
            }
            MenuPage.Shape -> VideoScale.entries.forEach { shape ->
                Entry(shape.label, checked = shape == status.scale) { pick(MenuAction.Shape(shape)) }
            }
            MenuPage.Bookmarks -> {
                Entry(s.playerAddBookmark, trailing = "B") { pick(MenuAction.AddBookmark) }
                HorizontalDivider()
                val list = bookmarks.orEmpty()
                if (list.isEmpty()) {
                    Text(
                        s.playerBookmarksEmpty,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 320.dp),
                    )
                }
                list.forEach { mark ->
                    BookmarkLine(
                        mark,
                        onJump = { pick(MenuAction.JumpTo(mark)) },
                        onEdit = { pick(MenuAction.EditBookmark(mark)) },
                        onDelete = { onAction(MenuAction.DeleteBookmark(mark)) },
                    )
                }
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
        delay(650L)
        alpha.animateTo(0f, tween(300))
    }
    val layer = Modifier.graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
    when (f.kind) {
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
                Icon(if (f.text == s.playerMuted || f.text == s.messages.formatter.percent(0.0)) TmIcons.VolumeOff else TmIcons.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
            .clip(CircleShape).background(Color(0xCC1C1C1E)).padding(start = 16.dp, end = 4.dp),
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
        Surface(shape = RoundedCornerShape(12.dp), color = NextUpSurface, modifier = Modifier.width(300.dp)) {
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

/** The next-up card's surface, which the skip pill shares so the two read as one family. */
private val NextUpSurface = Color(0xE61C1C1E)

/** The words on the pill for each kind of segment: "Skip intro", "Skip credits" and the rest. */
internal fun skipLabel(kind: SkipKind, s: com.tmplayer.i18n.Strings): String = when (kind) {
    SkipKind.Intro -> s.playerSkipIntro
    SkipKind.Recap -> s.playerSkipRecap
    SkipKind.Opening -> s.playerSkipOpening
    SkipKind.Ending -> s.playerSkipEnding
    SkipKind.Credits -> s.playerSkipCredits
    SkipKind.Preview -> s.playerSkipPreview
}

/**
 * "Skip intro", in the corner the next-up card uses, for as long as the playhead is inside the
 * segment. It is drawn outside the controls overlay, so it stays when they fade, and rises above
 * the bar ([lifted]) when they are up, as the card does. G does the same; the tooltip says so,
 * since the "?" sheet is a long way from the moment it is wanted.
 */
@Composable
internal fun BoxScope.SkipPill(kind: SkipKind, lifted: Boolean, onSkip: () -> Unit) {
    val s = LocalStrings.current
    val label = skipLabel(kind, s)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    AnimatedVisibility(
        true,
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (lifted) 120.dp else 24.dp),
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        Tip("$label (G)") {
            Row(
                Modifier.testTag("skip-pill").height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(NextUpSurface)
                    .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                    .hoverable(hover)
                    .clickable(onClick = onSkip)
                    .padding(start = 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Icon(TmIcons.SkipNext, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * "Back to 12:34" after a long jump ([com.tmplayer.player.SeekUndo]): bottom left, where the
 * resume notice sits, since it answers the same question (where was I?). Slides in from its edge
 * (fades only with reduced motion), sinks a little under the press, and takes the viewer back.
 * Shift+Backspace does the same; the tooltip says so.
 */
@Composable
internal fun BoxScope.UndoSeekChip(backToMs: Long, lifted: Boolean, onUndo: () -> Unit) {
    val s = LocalStrings.current
    val reduced = com.tmplayer.ui.theme.rememberReducedMotion()
    val label = s.playerBackTo(SeekMath.clock(backToMs))
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !reduced) 0.94f else 1f,
        tween(if (reduced) 0 else 120),
        label = "undo-press",
    )
    AnimatedVisibility(
        shown,
        Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (lifted) 120.dp else 24.dp),
        enter = if (reduced) fadeIn(tween(120)) else slideInHorizontally(tween(220, easing = Motion.Standard)) { -it } + fadeIn(tween(220)),
        exit = fadeOut(tween(120)),
    ) {
        Tip("$label (${s.keysShiftBackspace})") {
            Row(
                Modifier.testTag("undo-seek").graphicsLayer { scaleX = scale; scaleY = scale }
                    .height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(NextUpSurface)
                    .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onUndo)
                    .padding(start = 14.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(TmIcons.History, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
        Modifier.padding(start = 8.dp).height(44.dp).clip(CircleShape)
            .background(ControlsPillFill)
            .border(1.dp, ControlsPillEdge, CircleShape)
            .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(TmIcons.Episodes, null, tint = Color.White, modifier = Modifier.size(22.dp))
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
                modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                focusCurrent = true,
                trailing = { OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 36) },
            )
        }
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
                OverlayButton(TmIcons.Close, s.playerCloseDetailsHint, onClose, size = 32)
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
    /** For a video refused for room ([Phase.Failed.noRoom]): Downloads, with Free up space up. */
    onFreeUpSpace: (() -> Unit)? = null,
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
                        // Short of room, trying again finds the same drive: the first button is
                        // the one that makes room, focused so Enter takes it.
                        if (phase.noRoom && onFreeUpSpace != null) {
                            val free = remember { FocusRequester() }
                            Button(onClick = onFreeUpSpace, modifier = Modifier.focusRequester(free)) { Text(s.downloadsFreeUpSpace) }
                            Spacer(Modifier.width(8.dp))
                            LaunchedEffect(phase) { runCatching { free.requestFocus() } }
                        }
                        // Otherwise Try again takes the keyboard, so Enter answers the sheet.
                        val retry = remember { FocusRequester() }
                        TextButton(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(s.commonTryAgain) }
                        TextButton(onClick = onBack) { Text(s.commonBack) }
                        if (!(phase.noRoom && onFreeUpSpace != null)) LaunchedEffect(phase) { runCatching { retry.requestFocus() } }
                    }
                    // The logs to send with a report of it: saved and shown, or straight to GitHub.
                    Spacer(Modifier.height(8.dp))
                    com.tmplayer.desktop.ui.SendLogsActions()
                    // What went wrong, in words a report or a forum post can carry.
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    var copied by remember(phase) { mutableStateOf(false) }
                    TextButton(onClick = {
                        val details = buildString {
                            appendLine("TMPlayer ${com.tmplayer.desktop.BuildInfo.VERSION}, ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                            appendLine(if (subtitle.isNotBlank()) "$title ($subtitle)" else title)
                            appendLine(phase.message)
                        }
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(details))
                        copied = true
                    }) { Text(if (copied) s.playerDetailsCopied else s.playerCopyDetails) }
                }
                is Phase.Countdown -> {
                    Text(s.playerNextUp(nextLabel(phase.next)), color = Color.White, fontSize = 17.sp)
                    Text(s.playerStartingIn(phase.seconds), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        val now = remember { FocusRequester() }
                        TextButton(onClick = { onPlayNext(phase.next) }, modifier = Modifier.focusRequester(now)) { Text(s.playerPlayNow) }
                        TextButton(onClick = onCancelNext) { Text(s.commonCancel) }
                        // Keyed on the episode, not the second, so the count does not steal focus each tick.
                        LaunchedEffect(phase.next) { runCatching { now.requestFocus() } }
                    }
                }
                Phase.Finished -> {
                    Text(s.playerTheEnd, color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        val again = remember { FocusRequester() }
                        TextButton(onClick = onWatchAgain, modifier = Modifier.focusRequester(again)) { Text(s.playerWatchAgain) }
                        TextButton(onClick = onBack) { Text(s.commonBack) }
                        LaunchedEffect(Unit) { runCatching { again.requestFocus() } }
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
                    OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 32)
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

/** The command key as this OS spells it in a hint: Cmd on a Mac, where [PlayerKeys] reads Cmd, Ctrl elsewhere. */
private fun commandKey(s: Strings): String = if (OsInfo.isMac) s.keysCmd else s.keysCtrl

/**
 * The centre play and pause button. Its own change is the only answer to a pause or a resume:
 * the glyph turns over with a short scale and fade (at once with reduced motion), and a screen
 * reader hears "Paused" or "Playing" as it changes.
 */
@Composable
private fun PlayPauseButton(status: PlaybackStatus, onTogglePlay: () -> Unit) {
    val s = LocalStrings.current
    val reduced = com.tmplayer.ui.theme.rememberReducedMotion()
    Tip("${if (status.playing) s.playerPause else s.playerPlay} (${s.keysSpaceK})") {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(PlayerTonalButton).clickable(onClick = onTogglePlay)
                .testTag("play-pause")
                .semantics {
                    stateDescription = if (status.playing) s.playerPlaying else s.playerPaused
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.Center,
        ) {
            if (status.buffering) {
                CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
            } else {
                androidx.compose.animation.AnimatedContent(
                    targetState = status.playing,
                    transitionSpec = {
                        if (reduced) {
                            androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        } else {
                            (scaleIn(tween(180), initialScale = 0.6f) + fadeIn(tween(180))) togetherWith
                                (scaleOut(tween(140), targetScale = 0.6f) + fadeOut(tween(140)))
                        }
                    },
                    label = "play-pause",
                ) { playing ->
                    Icon(if (playing) TmIcons.PauseSolid else TmIcons.PlaySolid, if (playing) s.playerPause else s.playerPlay, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

/** A group's title inside the 3D video page. */
@Composable
private fun StereoHeading(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** The glasses a [view] is for, as the 3D page and its flash name them. */
internal fun stereoGlassesLabel(view: Stereo3d.View): String = when (view) {
    Stereo3d.View.RedCyan -> L.playerStereoRedCyan
    Stereo3d.View.GreenMagenta -> L.playerStereoGreenMagenta
    Stereo3d.View.YellowBlue -> L.playerStereoYellowBlue
    Stereo3d.View.OneEye -> L.playerStereoOneEye
    Stereo3d.View.Vr -> L.playerStereoVr
}

/** The 3D line's detail on the menu's first page: Off, or the layout and the glasses. */
internal fun stereoSummary(stereo: Stereo3d): String = when (stereo.layout) {
    Stereo3d.Layout.Off -> L.commonOff
    Stereo3d.Layout.SideBySide -> L.playerStereoDetail(L.playerStereoSideBySide, stereoGlassesLabel(stereo.view))
    Stereo3d.Layout.TopBottom -> L.playerStereoDetail(L.playerStereoTopBottom, stereoGlassesLabel(stereo.view))
    Stereo3d.Layout.RowInterleaved -> L.playerStereoDetail(L.playerStereoRows, stereoGlassesLabel(stereo.view))
    Stereo3d.Layout.ColumnInterleaved -> L.playerStereoDetail(L.playerStereoColumns, stereoGlassesLabel(stereo.view))
}
