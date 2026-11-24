package com.tmplayer.desktop.ui

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusDrawn
import com.tmplayer.ui.theme.focusRingColor
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.ui.theme.Focus
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.CarouselDots
import com.tmplayer.ui.browse.FeaturedInfo
import com.tmplayer.ui.browse.HeroPill
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.seriesPlayLabel
import com.tmplayer.ui.components.EdgeBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The featured carousel at the top of Home, the phone's brought to the window: a mix of the newest
 * films, shows and videos in the starred chats and what the viewer is part way through
 * ([rememberFeatured]), one wide page each: the name (its logo where TMDB has one), a line
 * of facts and the overview ([FeaturedInfo]), Play and Details, with the dots at the foot of the
 * end ([CarouselDots]), moving on by itself every [ADVANCE_MS]. One picture stands under the
 * pages and cross-fades as they turn, and the words cross-fade in place over it. It takes Home's title's place: the page has no "Home" heading.
 *
 * A window is not a phone, so the words keep to the start, where they read against the picture's
 * fade, and every way a computer has of moving sideways turns the page, one page a time:
 *
 * - a touchpad's two finger swipe, a tilting wheel, or Shift and the wheel: one page per gesture,
 *   however long its momentum runs on ([SwipeSteps]); the plain wheel is left to the page, which
 *   scrolls on under the carousel as anywhere else;
 * - a drag with the mouse or a finger, which snaps to the nearer page;
 * - the dots at the foot, each a button for its page (no arrow buttons at the sides: a hero picture
 *   with buttons floating over it was clutter, HX65; the rows below keep their arrows);
 * - Left and Right once the carousel has the keyboard (Tab reaches it, with a ring round it), with
 *   Enter for the page's details and Space to play it, as on Home's cards.
 *
 * It holds still while the pointer is over it, the keyboard is in it, a drag is under way, and
 * with reduced motion. It is as tall from its first frame as with its pages in, a shimmering
 * stand-in while [picks] is null, so nothing on Home moves when they land.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FeaturedCarousel(
    picks: List<FeaturedPick>?,
    height: Dp,
    side: Dp,
    watch: SeriesWatch,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem) -> Unit,
    onDetails: (FeaturedPick) -> Unit,
    modifier: Modifier = Modifier,
    /** How far down the picture fades into the page; null is the desktop's, [TOP_FADE]. Deeper under the big-screen bar. */
    topFade: Dp? = null,
) {
    Box(modifier.fillMaxWidth().height(height)) {
        if (picks == null) {
            FeaturedSkeleton(side)
            return@Box
        }
        if (picks.isEmpty()) return@Box
        val s = LocalStrings.current
        val count = picks.size
        val latest by rememberUpdatedState(picks)
        var currentKey by remember { mutableStateOf(picks.first().key) }
        val pager = rememberPagerState(initialPage = picks.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)) { count }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { page -> latest.getOrNull(page)?.let { currentKey = it.key } }
        }
        LaunchedEffect(picks) {
            val wanted = picks.indexOfFirst { it.key == currentKey }
            if (wanted >= 0 && wanted != pager.currentPage) pager.scrollToPage(wanted)
        }
        // Every page's title logo looked up, fetched and decoded ahead, from the page up on, so a
        // turn shows the next page's logo at once.
        com.tmplayer.ui.online.PrefetchTitleLogos(remember(picks) { picks.map { it.item to it.isShow } }, pager.currentPage)

        val still = rememberReducedMotion() || Motion.reduced
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        val dragged by pager.interactionSource.collectIsDraggedAsState()
        var focusedWithin by remember { mutableStateOf(false) }
        var focusedSelf by remember { mutableStateOf(false) }
        // A touch holds the pages still for a while: a touch screen has no hover to hold them with.
        var touchedAt by remember { mutableStateOf(0L) }
        var touchHeld by remember { mutableStateOf(false) }
        LaunchedEffect(touchedAt) {
            if (touchedAt == 0L) return@LaunchedEffect
            touchHeld = true
            delay(TOUCH_HOLD_MS)
            touchHeld = false
        }
        val auto = count > 1 && !still
        val paused = hovered || focusedWithin || dragged || touchHeld
        val progress = remember { Animatable(0f) }
        var progressPage by remember { mutableIntStateOf(-1) }
        val settled = pager.settledPage
        LaunchedEffect(settled, paused, auto, count) {
            if (progressPage != settled) {
                progress.snapTo(0f)
                progressPage = settled
            }
            if (!auto || paused) return@LaunchedEffect
            val left = (1f - progress.value).coerceIn(0f, 1f)
            progress.animateTo(1f, tween((ADVANCE_MS * left).toInt(), easing = LinearEasing))
            pager.animateScrollToPage((settled + 1) % count)
        }
        val scope = rememberCoroutineScope()
        // Where the last turn is headed, so a second press before the first has landed goes one
        // further rather than to the same page again.
        var target by remember { mutableIntStateOf(pager.currentPage) }
        LaunchedEffect(settled) { target = settled }
        val go: (Int) -> Unit = { page ->
            val next = (page + count) % count
            target = next
            scope.launch { if (still) pager.scrollToPage(next) else pager.animateScrollToPage(next) }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val steps = remember { SwipeSteps() }
        val self = remember { FocusRequester() }
        val ring = Tone.accent

        // A frame resized under a page (the detail pane opening beside Home) lands back on that
        // page whole, never part way into the next.
        var frameWidth by remember { mutableIntStateOf(0) }
        LaunchedEffect(frameWidth) {
            if (frameWidth > 0 && !pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f) pager.scrollToPage(pager.currentPage)
        }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { frameWidth = it.width }
                .hoverable(hover)
                // A mouse turns the pages by dragging too: Compose moves a pager under a finger
                // only, so on the desktop and in the browser the picture would not follow a drag.
                .pointerInput(count, rtl) {
                    if (count < 2) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != PointerType.Mouse) return@awaitEachGesture
                        var travelled = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - change.previousPosition.x
                            travelled += dx
                            if (!dragging && abs(travelled) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                change.consume()
                                pager.dispatchRawDelta(if (rtl) dx else -dx)
                            }
                        }
                        if (dragging) {
                            // A third of the width, or a flick past the slop, is a turn.
                            val fraction = pager.currentPageOffsetFraction
                            val page = when {
                                fraction > DRAG_TURN -> pager.currentPage + 1
                                fraction < -DRAG_TURN -> pager.currentPage - 1
                                else -> pager.currentPage
                            }.coerceIn(0, count - 1)
                            target = page
                            scope.launch { if (still) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    if (event.changes.any { it.type == PointerType.Touch }) touchedAt = System.nanoTime()
                }
                // Sideways scrolls turn the page here, one per gesture, before the pager could
                // scroll by them; an upright one is the page's and is left alone.
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    if (count < 2) return@onPointerEvent
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val delta = change.scrollDelta
                    if (delta.x == 0f || abs(delta.y) > abs(delta.x)) return@onPointerEvent
                    event.changes.forEach { it.consume() }
                    val step = steps.feed(if (rtl) -delta.x else delta.x, change.uptimeMillis)
                    if (step != 0) go(target + step)
                }
                .onFocusChanged { focusedWithin = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val back = if (rtl) Key.DirectionRight else Key.DirectionLeft
                    val on = if (rtl) Key.DirectionLeft else Key.DirectionRight
                    when {
                        count > 1 && (event.key == back || event.key == on) -> {
                            go(target + if (event.key == on) 1 else -1)
                            // A button on the page left behind would keep the keys off screen.
                            if (!focusedSelf) self.requestFocus()
                            true
                        }
                        focusedSelf && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            latest.getOrNull(pager.currentPage)?.let(onDetails)
                            true
                        }
                        focusedSelf && event.key == Key.Spacebar -> {
                            latest.getOrNull(pager.currentPage)?.let { pick -> onPlay(playTarget(pick, watch)) }
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(self)
                .onFocusChanged { focusedSelf = it.isFocused }
                .focusable()
                .semantics { contentDescription = s.homeFeaturedPage(pager.currentPage + 1, count) }
                .drawWithContent {
                    drawContent()
                    if (focusedSelf && com.tmplayer.ui.theme.FocusVisibility.visible) {
                        val width = Focus.Ring.toPx()
                        drawRect(ring, topLeft = Offset(width / 2, width / 2), size = Size(size.width - width, size.height - width), style = Stroke(width))
                    }
                },
        ) {
            // One picture for the carousel, cross-faded from page to page under the words, rather
            // than a picture on every page sliding with it: a sliding page showed its picture's
            // end as a hard edge across the frame and the next page's start peeking in beside it.
            val up = latest.getOrNull(pager.currentPage)
            androidx.compose.animation.Crossfade(
                targetState = up,
                animationSpec = Motion.standard(Motion.Slow),
                modifier = Modifier.fillMaxSize(),
                label = "featuredBackdrop",
            ) { shown ->
                if (shown != null) FeaturedBackdrop(shown, chatTitle, topFade ?: TOP_FADE)
            }
            // The picture keeps its right side; its left side and its foot go into the page's colour
            // under the words, as Netflix's, Plex's and Disney+'s heroes do, so the words read on
            // the faded part and the picture shows on the right even in a small window.
            HeroScrim(Modifier.fillMaxSize())
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { latest.getOrNull(it)?.key ?: it },
            ) { page ->
                val pick = latest.getOrNull(page) ?: return@HorizontalPager
                // The words stay where they are and cross-fade, as the picture under them does: the
                // pager's own slide carried them across the picture, a sentence of the next film
                // passing over the face of the last. The leaving words are gone by the half turn
                // and the coming ones only then appear, so two pages of words never show at once.
                val offset = { (pager.currentPage - page) + pager.currentPageOffsetFraction }
                FeaturedPage(
                    pick, side, watch, chatTitle, onPlay, onDetails,
                    current = page == pager.currentPage,
                    modifier = Modifier.graphicsLayer {
                        val o = offset()
                        // Only a page in the turn is held in place: a neighbour at rest stays off
                        // the frame, where its invisible buttons cannot take a click meant for Play.
                        translationX = if (abs(o) < 1f) o * size.width else 0f
                        alpha = (1f - abs(o).coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f)
                    },
                )
            }
            // Which page is up, at the foot of the end, level with Play; a click goes to that page.
            // The pages also turn by the arrow keys, a drag, a sideways swipe and the auto advance.
            CarouselDots(
                count = count,
                current = pager.currentPage,
                onPick = { page -> go(page) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = side, bottom = PAGE_FOOT + (com.tmplayer.ui.browse.HERO_PILL_HEIGHT - DOTS_ROW) / 2)
                    .height(DOTS_ROW),
            )
        }
    }
}

/**
 * Turns a run of sideways scroll deltas into page turns: one turn as soon as a gesture has moved
 * [threshold] wheel steps one way, then nothing more until the deltas stop for [quietMs], so a
 * touchpad's momentum, or a wheel spun hard, still turns one page and not five. A pause between
 * two swipes longer than that starts a new gesture.
 */
internal class SwipeSteps(private val threshold: Float = SWIPE_THRESHOLD, private val quietMs: Long = SWIPE_QUIET_MS) {
    private var sum = 0f
    private var last = Long.MIN_VALUE
    private var spent = false

    /** +1 for the next page, -1 for the previous, 0 for nothing yet. [atMs] is the event's time. */
    fun feed(delta: Float, atMs: Long): Int {
        if (last == Long.MIN_VALUE || atMs - last > quietMs) {
            sum = 0f
            spent = false
        }
        last = atMs
        if (spent || delta == 0f) return 0
        // A turn back the other way within one gesture starts the count again.
        if (sum != 0f && sign(sum) != sign(delta)) sum = 0f
        sum += delta
        if (abs(sum) < threshold) return 0
        spent = true
        val step = if (sum > 0f) 1 else -1
        sum = 0f
        return step
    }
}

/** What Play plays for [pick]: the film, or the episode of the show the viewer is up to. */
private fun playTarget(pick: FeaturedPick, watch: SeriesWatch): MediaItem {
    val series = pick.series ?: return pick.item
    val progress = watch.progress(series)
    val episode = progress.next ?: series.episodes.firstOrNull() ?: return pick.item
    return watch.pick(episode, progress)
}

/**
 * The picture of the page up, at the end, melting into the page at its start, its foot and its
 * top: the words at the start stand on the page, and the window's buttons over the top fade.
 */
@Composable
private fun FeaturedBackdrop(pick: FeaturedPick, chatTitle: (Long) -> String, topFade: Dp) {
    val item = pick.item
    val meta = rememberMeta(item, showOnly = pick.isShow)
    val cover = rememberCover(item, chatTitle(item.chatId))
    val source = remember(item, meta?.wideUrl, meta?.posterUrl, cover) { BackdropSource(item.miniThumbnail, item.thumbnailFileId, cover, meta?.wideUrl, meta?.posterUrl) }
    EdgeBackdrop(source, Modifier.fillMaxSize(), topFade = topFade, overhang = if (BigScreen.on) com.tmplayer.ui.components.EDGE_OVERHANG else 0f, onPictured = com.tmplayer.ui.browse.HomeFirstPaint::markReady)
}

/**
 * The hero's scrim: the page's colour solid at the start edge over the words' column, clear by
 * [SCRIM_REACH] of the width, and a short fade into the page at the foot ([SCRIM_FOOT]), so the
 * picture ends in the page rather than in an edge above the first row.
 */
@Composable
private fun HeroScrim(modifier: Modifier = Modifier) {
    val ground = Tone.background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier.drawWithCache {
            val across = Brush.horizontalGradient(
                0f to ground,
                0.22f to ground.copy(alpha = 0.86f),
                0.40f to ground.copy(alpha = 0.45f),
                SCRIM_REACH to Color.Transparent,
                startX = if (rtl) size.width else 0f,
                endX = if (rtl) 0f else size.width,
            )
            val foot = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to ground,
                startY = (size.height - SCRIM_FOOT.toPx()).coerceAtLeast(0f),
                endY = size.height,
            )
            onDrawBehind {
                drawRect(across)
                drawRect(foot)
            }
        },
    )
}

/** How far across the hero the start scrim reaches before it is clear: a little over half the width. */
internal const val SCRIM_REACH = 0.55f

/** How tall the fade into the page at the hero's foot is. */
private val SCRIM_FOOT = 96.dp

/**
 * One page's words over the carousel's picture: the name (or its logo), the facts and the
 * overview ([FeaturedInfo]), Play and Details.
 */
@Composable
private fun FeaturedPage(
    pick: FeaturedPick,
    side: Dp,
    watch: SeriesWatch,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem) -> Unit,
    onDetails: (FeaturedPick) -> Unit,
    current: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val item = pick.item
    val series = pick.series
    val chat = chatTitle(item.chatId)
    val meta = rememberMeta(item, showOnly = pick.isShow)
    val title = remember(pick) { series?.title?.ifBlank { null } ?: pick.loneEpisode?.title ?: SeriesShelf.tileTitle(item) }
    val facts = remember(pick, chat, s) { featuredFacts(pick, title, chat, s) }
    val showProgress = remember(series, watch) { series?.let { watch.progress(it) } }
    val episode = series?.let { showProgress?.next ?: it.episodes.firstOrNull() }
    val playItem = if (series != null && episode != null && showProgress != null) watch.pick(episode, showProgress) else item
    val playLabel = if (series != null && episode != null && showProgress != null) {
        seriesPlayLabel(episode.code, watch.pointOf(episode), showProgress.watched > 0)
    } else if (pick.loneEpisodeCode != null) {
        // A lone episode on its show's page: Resume or Play names the episode, as a show's page does.
        seriesPlayLabel(pick.loneEpisodeCode.orEmpty(), watch.point(item), anyWatched = false)
    } else if ((watch.point(item)?.positionMs ?: 0L) > 0L) {
        s.commonResume
    } else {
        s.commonPlay
    }
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = listOf(title, facts).filter { it.isNotBlank() }.joinToString(". ") },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                // Only the page up takes the keyboard: Tab never lands on a button off screen.
                .focusProperties { canFocus = current }
                .padding(start = side, end = side, bottom = PAGE_FOOT)
                .widthIn(max = WORDS_WIDTH),
            verticalArrangement = Arrangement.spacedBy(WORDS_GAP),
        ) {
            // The title's logo where TMDB has one, the name in type until then and otherwise.
            com.tmplayer.ui.online.TitleArt(meta, maxWidth = DESKTOP_LOGO_MAX_WIDTH, maxHeight = DESKTOP_LOGO_MAX_HEIGHT) {
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FeaturedInfo(pick, meta, Modifier.widthIn(max = INFO_WIDTH), overviewLines = 3, scoreLogoHeight = 22.dp, scoreLines = 2, leftSec = remember(pick, watch) { watch.leftSec(pick) })
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroPill(label = playLabel, onClick = { onPlay(playItem) }, modifier = Modifier.warmUpOnIntent(playItem))
                RoundButton(TmIcons.Info, s.detailOpenDetails, size = com.tmplayer.ui.browse.HERO_PILL_HEIGHT) { onDetails(pick) }
            }
        }
    }
}

/** A round button over the picture in the floating look: Details. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    BarTooltip(label, null) {
        Box(
            Modifier
                .size(size)
                .pressScale(press)
                .clip(CircleShape)
                .background(FloatingTone.menu.copy(alpha = 0.85f))
                .border(floatingBorder(), CircleShape)
                .focusDrawn(press.collectIsFocusVisibleAsState(), focusRingColor(), CircleShape)
                .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(22.dp))
        }
    }
}

/** The year when the name does not carry it, "Season 2" or "6 versions", the quality, the chat. */
private fun featuredFacts(pick: FeaturedPick, title: String, chat: String, s: com.tmplayer.i18n.Messages): String {
    val item = pick.item
    val series = pick.series
    val name = SeriesShelf.episodeOf(item)
    val year = name.year?.takeIf { series == null && it.toString() !in title }?.toString()
    val which = when {
        series != null -> series.seasons.maxOfOrNull { it.number }?.takeIf { it > 0 }?.let { s.seriesSeason(it) }
        com.tmplayer.data.VideoCopies.versionCount(item) > 1 -> s.detailVersions(com.tmplayer.data.VideoCopies.versionCount(item))
        else -> null
    }
    return listOfNotNull(year, which, com.tmplayer.data.QualityRead.of(item), chat.ifBlank { null }).joinToString("  ·  ")
}

@Composable
private fun FeaturedSkeleton(side: Dp) {
    val shimmer = rememberShimmer()
    Box(Modifier.fillMaxSize()) {
        SkeletonBox(shimmer, Modifier.fillMaxSize(), RectangleShape)
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = side, bottom = 12.dp).widthIn(max = WORDS_WIDTH).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.7f).height(40.dp), RoundedCornerShape(8.dp))
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.45f).height(16.dp), RoundedCornerShape(8.dp))
            SkeletonBox(shimmer, Modifier.padding(top = 6.dp).fillMaxWidth(0.4f).height(com.tmplayer.ui.browse.HERO_PILL_HEIGHT), CircleShape)
        }
    }
}

/**
 * The carousel's pages: what [HomeFeatured.pick] chooses from the Recently added row [recent],
 * mixed with Continue watching [resume] ([HomeFeatured.mix]): films and shows of at least
 * [HomeFeatured.MIN_BYTES] whose pages are complete ([com.tmplayer.ui.online.completeFeatured]).
 * A Continue watching record joins once its video is in [art], with no match or picture needed
 * (it is drawn on its generated cover). Null while Recently added is still
 * being asked for or the fill is deciding (the skeleton, at most [HomeFeatured.FILL_WAIT_MS] once
 * the row is in), empty for nothing to feature, which hides the carousel. Decided once per fill, so
 * no page comes or goes while it is looked at; with online lookups off, empty at once.
 */
@Composable
internal fun rememberFeatured(recent: HomeRow.Recent?, resume: HomeRow.Continue?, art: Map<String, MediaItem>): List<FeaturedPick>? {
    val online = OnlineMetadata.current
    val enabled = online?.settings?.collectAsState()?.value?.enabled == true
    // More than a carousel's worth, so pages without a match or a picture can be passed over.
    val fresh = remember(recent) {
        recent?.takeIf { it.loaded }?.let { HomeFeatured.pick(it.entries, HomeFeatured.CANDIDATES - HomeFeatured.LIMIT) }
    }
    val going = remember(resume, art) {
        resume?.records.orEmpty().mapNotNull { art[com.tmplayer.data.SettingsStore.progressKey(it.chatId, it.messageId)] }
    }
    // Looked up: the new pages, and a carousel's worth of Continue watching's films and shows. A
    // record's picture arriving refills only when it adds one of those, not for every card.
    val resumable = remember(going) {
        going.asSequence().map { FeaturedPick(com.tmplayer.data.ShelfEntry.File(it)) }
            .filter(HomeFeatured::eligible).take(HomeFeatured.LIMIT).toList()
    }
    val resumeKeys = remember(resumable) { resumable.map { it.key } }
    val latestResumable by androidx.compose.runtime.rememberUpdatedState(resumable)
    var decided by remember { mutableStateOf<List<FeaturedPick>?>(null) }
    LaunchedEffect(fresh, resumeKeys, online, enabled) {
        val resumable = latestResumable
        if (!enabled) {
            decided = emptyList()
            return@LaunchedEffect
        }
        // Recently added still on its way: the skeleton, not the empty carousel worked out before
        // the row was there, which dropped the frame and let the loader go before the carousel.
        if (recent != null && fresh == null) {
            decided = null
            return@LaunchedEffect
        }
        if ((fresh.orEmpty() + resumable).isEmpty()) {
            decided = emptyList()
            return@LaunchedEffect
        }
        decided = com.tmplayer.ui.online.mixedFeatured(fresh.orEmpty(), resumable, resumeFirst = false, scope = this)
    }
    return if (!enabled) emptyList() else decided
}

/**
 * How tall the carousel is in a page [width] by [height]: [FEATURED_HEIGHT_SHARE] of the window's
 * height, never under [MIN_HEIGHT] nor over [MAX_HEIGHT]. It was the width's 0.42 up to 0.62 of
 * the height, which on a 1080p window stood 636 dp tall and pushed Continue watching under the
 * fold with its heading half over the picture (user, 2026-11-25: "a bit shorter carousel").
 * The figure comes from the window alone, computed the same on every visit to Home and for the
 * skeleton, so nothing on Home moves when pages load or when the page is come back to. [width] is
 * kept for the callers' sake and no longer matters: the picture is as wide as the window anyway.
 */
@Suppress("UNUSED_PARAMETER")
internal fun featuredHeight(width: Dp, height: Dp): Dp =
    (height * FEATURED_HEIGHT_SHARE).coerceIn(MIN_HEIGHT, MAX_HEIGHT)

internal const val FEATURED_HEIGHT_SHARE = 0.58f

private const val ADVANCE_MS = 6_000

/** How far into the next page a mouse drag has to carry the picture to turn to it. */
private const val DRAG_TURN = 0.2f

/** How long the pages hold still after a touch. */
private const val TOUCH_HOLD_MS = 4_000L

/** Wheel steps a sideways gesture has to move before it turns the page: half a notch. */
private const val SWIPE_THRESHOLD = 0.5f

/** A gap this long in the scroll events ends a gesture, its momentum included. */
private const val SWIPE_QUIET_MS = 280L
internal val MIN_HEIGHT = 360.dp

/** The tallest the carousel gets, on a big monitor. */
internal val MAX_HEIGHT = 560.dp
/**
 * How far down the picture fades into the page from the top, where a window's buttons stand: a
 * band across the whole width rather than the glow in the corner it replaced, which showed as a
 * pale blot on the picture in the light theme.
 */
private val TOP_FADE = 48.dp

/** The facts and the overview: a comfortable measure for three lines. */
private val INFO_WIDTH = 560.dp

/** The dots' row: a 44 dp target round the small marks. */
private val DOTS_ROW = 44.dp
private val WORDS_WIDTH = 640.dp

/*
 * The hero's words and buttons start on the page's gutter ([side]), the same left edge as the row
 * headings and the cards under it (they had 10 dp more, which read as a misplaced block).
 */

/** Below Play, the foot of a page's words and buttons. */
private val PAGE_FOOT = 12.dp

/** Between a page's name and Play. */
private val WORDS_GAP = 8.dp

/** The most a title logo takes over the desktop's carousel. */
private val DESKTOP_LOGO_MAX_WIDTH = 420.dp
private val DESKTOP_LOGO_MAX_HEIGHT = 130.dp
