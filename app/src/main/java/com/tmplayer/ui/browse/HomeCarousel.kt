package com.tmplayer.ui.browse

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.view.WindowCompat
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.ui.components.EdgeBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * The phone's featured carousel at the top of Home: the newest five different films, shows and
 * videos in the starred chats ([HomeFeatured]), one full-bleed page each, swiped through. Only the
 * picture and the name move with a swipe: Play and Details stay put at the foot, acting on the page
 * up, and the pages move on by themselves every [ADVANCE_MS]. Over the buttons the name (its
 * logo where TMDB has one), a line of facts and two lines of the overview ([FeaturedInfo]); under
 * them the dots ([CarouselDots]) say which page is up.
 *
 * On its side (a frame wider than [WIDE_RATIO] times its height: a phone held sideways, a tablet
 * lying down) it takes the television's and the desktop's arrangement instead: the picture stands
 * at the end and melts into the page towards the start ([EdgeBackdrop]), the words and the buttons
 * sit at the start on about half the width (Play at its own width, Details round beside it), and
 * the dots stand at the end, level with Play. Centred words over a stretched Play read as a phone
 * screen blown up across a frame three times as wide as it is tall.
 *
 * It is [height] tall from its first frame, whatever has arrived: while [picks] is null (the
 * starred chats are still being asked) the same frame holds a shimmering stand-in, so nothing on
 * Home moves when the pages land. The pictures run up under the status bar ([under]).
 *
 * The pages move on by themselves only while nobody is touching them, Home is [active] (on screen,
 * with no sheet or dialog over it), motion is on, and TalkBack is off. Only the page up and its
 * neighbours are composed, so only they fetch pictures.
 */
@Composable
internal fun FeaturedCarousel(
    picks: List<FeaturedPick>?,
    height: Dp,
    under: Dp,
    start: Dp,
    end: Dp,
    watch: SeriesWatch,
    chatTitle: (Long) -> String,
    active: Boolean,
    onPlay: (MediaItem, String) -> Unit,
    onDetails: (FeaturedPick) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        // The same test as the picture's own ([EdgeBackdrop]), so the words move to the start
        // exactly when the picture moves to the end.
        val wide = maxWidth > maxHeight * WIDE_RATIO
        // A tablet lying down has room for a third line of the overview; a phone on its side not.
        val overviewLines = if (wide && maxHeight >= TALL_WIDE) 3 else 2
        if (picks.isNullOrEmpty()) {
            FeaturedSkeleton(start, end, wide)
            return@BoxWithConstraints
        }
        val s = LocalStrings.current
        val count = picks.size
        val latest by rememberUpdatedState(picks)
        // The page up, by key, so a carousel that refills stays on the page being looked at.
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
        val talkBack = rememberTouchExploration()
        val auto = count > 1 && !still && !talkBack
        var held by remember { mutableStateOf(false) }
        val dragged by pager.interactionSource.collectIsDraggedAsState()
        val paused = held || dragged || !active
        val progress = remember { Animatable(0f) }
        var progressPage by remember { mutableIntStateOf(-1) }
        val settled = pager.settledPage
        LaunchedEffect(settled, paused, auto, count) {
            // A new page starts its time from empty; a pause keeps it where it stopped.
            if (progressPage != settled) {
                progress.snapTo(0f)
                progressPage = settled
            }
            if (!auto || paused) return@LaunchedEffect
            val left = (1f - progress.value).coerceIn(0f, 1f)
            progress.animateTo(1f, tween((ADVANCE_MS * left).toInt(), easing = LinearEasing))
            pager.animateScrollToPage((settled + 1) % count)
        }
        Box(
            Modifier
                .fillMaxSize()
                // Watched without consuming anything, so the pager and the buttons get every touch.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        held = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            held = false
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pager,
                // Every sideways drag that starts on the carousel stays with it: one past the last
                // page does not carry on into the Home and Chats pager and turn Home over to Chats.
                modifier = Modifier.fillMaxSize().nestedScroll(KeepSideways),
                // The page up and its neighbours, and no further: only they fetch pictures.
                beyondViewportPageCount = 1,
                key = { latest.getOrNull(it)?.key ?: it },
            ) { page ->
                val pick = latest.getOrNull(page) ?: return@HorizontalPager
                FeaturedPage(
                    pick, height, under, start, end, chatTitle,
                    footRoom = if (wide) WIDE_CONTROLS_ROOM else CONTROLS_ROOM,
                    wide = wide,
                    overviewLines = overviewLines,
                )
            }
            if (wide) {
                // Play and Details at the start's foot, under the words; the dots at the end, level
                // with Play, a mark only as upright.
                latest.getOrNull(pager.currentPage)?.let { pick ->
                    FeaturedControls(
                        pick, watch, chatTitle, onPlay, onDetails,
                        wide = true,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = start, bottom = WIDE_FOOT),
                    )
                }
                CarouselDots(
                    count = count,
                    current = pager.currentPage,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = end, bottom = WIDE_FOOT + (HERO_PILL_HEIGHT - DOTS_ROOM) / 2),
                )
            } else Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = start, end = end, bottom = 4.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    latest.getOrNull(pager.currentPage)?.let { pick ->
                        FeaturedControls(pick, watch, chatTitle, onPlay, onDetails)
                    }
                    // Which page is up, under Play: a swipe turns the pages, so the dots are a mark only.
                    CarouselDots(count = count, current = pager.currentPage)
                }
            }
        }
    }
}

/** The row of dots under Play: a 6 dp mark with room above and below. Before [CONTROLS_ROOM], which reads it. */
private val DOTS_ROOM: Dp = 22.dp

/** What the controls at the carousel's foot take, which a page keeps its name clear of. */
private val CONTROLS_ROOM: Dp = 4.dp + HERO_PILL_HEIGHT + DOTS_ROOM

/** Under Play on its side, where the dots stand beside it rather than under it. */
private val WIDE_FOOT: Dp = 14.dp

/** What Play and Details take at the foot on its side, which a page keeps its name clear of. */
private val WIDE_CONTROLS_ROOM: Dp = WIDE_FOOT + HERO_PILL_HEIGHT

/**
 * One page: the picture under its fades and the name, clear of the controls ([footRoom]), centred
 * upright and at the start on its side ([wide]).
 */
@Composable
private fun FeaturedPage(
    pick: FeaturedPick,
    height: Dp,
    under: Dp,
    start: Dp,
    end: Dp,
    chatTitle: (Long) -> String,
    footRoom: Dp,
    wide: Boolean,
    overviewLines: Int,
) {
    val s = LocalStrings.current
    val item = pick.item
    val series = pick.series
    val chat = chatTitle(item.chatId)
    // A show's backdrop rather than one episode's still: the page stands for the whole show.
    val meta = rememberMeta(item, showOnly = pick.isShow)
    val cover = rememberCover(item, chat)
    val source = remember(item, meta?.wideUrl, meta?.posterUrl, cover) { BackdropSource(item.miniThumbnail, item.thumbnailFileId, cover, meta?.wideUrl, meta?.posterUrl) }
    val title = remember(pick) { series?.title?.ifBlank { null } ?: pick.loneEpisode?.title ?: SeriesShelf.tileTitle(item) }
    val facts = remember(pick, chat, s) { featuredFacts(pick, title, chat, s) }

    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = listOf(title, facts).filter { it.isNotBlank() }.joinToString(". ") },
    ) {
        // Upright the picture spans the width and melts into the page at its foot; on its side
        // it stands at the end as on a television, and the words sit on the page.
        EdgeBackdrop(source, Modifier.fillMaxSize(), onPictured = HomeFirstPaint::markReady)
        // The top darkened under the status bar, light in either theme.
        Box(
            Modifier
                .fillMaxWidth()
                .height(under + TOP_SCRIM)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        // Close over the buttons: the title's logo where TMDB has one (the name in type until then
        // and otherwise), the facts line and two lines of the overview, centred; on its side at
        // the start, on the page's own ground where the picture has faded out.
        if (wide) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(WIDE_WORDS_SHARE)
                    .padding(start = start, bottom = footRoom + TEXT_GAP),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.tmplayer.ui.online.TitleArt(meta, maxWidth = LOGO_MAX_WIDTH, maxHeight = WIDE_LOGO_MAX_HEIGHT) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Tone.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FeaturedInfo(pick, meta, Modifier.fillMaxWidth(), overviewLines = overviewLines)
            }
            return@Box
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = start, end = end, bottom = footRoom + TEXT_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tmplayer.ui.online.TitleArt(
                meta,
                maxWidth = LOGO_MAX_WIDTH,
                maxHeight = LOGO_MAX_HEIGHT,
                alignment = Alignment.BottomCenter,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FeaturedInfo(pick, meta, Modifier.fillMaxWidth(), overviewLines = 2, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Play and Details at the carousel's foot, for the page up; they stay put while the pages move.
 * Upright Play stretches across the foot; on its side ([wide]) it keeps its own width at the start.
 */
@Composable
private fun FeaturedControls(
    pick: FeaturedPick,
    watch: SeriesWatch,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onDetails: (FeaturedPick) -> Unit,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val item = pick.item
    val series = pick.series

    // What Play starts: a show where the viewer is in it (the episode under way, else the next),
    // a film or a video as itself, the copy to play chosen on the way (SettingsStore.copyToPlay).
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

    Row(
        if (wide) modifier else modifier.fillMaxWidth(),
        horizontalArrangement = if (wide) Arrangement.spacedBy(12.dp) else Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroPill(
            label = playLabel,
            onClick = { onPlay(playItem, chatTitle(playItem.chatId)) },
            stretch = !wide,
            modifier = if (wide) Modifier.warmUpOnIntent(playItem) else Modifier.weight(1f, fill = false).then(Modifier.warmUpOnIntent(playItem)),
        )
        com.tmplayer.ui.components.IconTip(s.detailOpenDetails) {
            Box(
                Modifier
                    .size(HERO_PILL_HEIGHT)
                    .clip(CircleShape)
                    .background(Tone.surfaceHigh.copy(alpha = 0.72f))
                    .border(1.dp, Tone.outline, CircleShape)
                    .clickable(role = Role.Button) { onDetails(pick) }
                    .semantics { contentDescription = s.detailOpenDetails },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TmIcons.Info, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * A page's facts: the year (when the name does not already carry it), "Season 2" for a show or
 * "6 versions" for a film posted several times, the quality, and the chat it came from.
 */
internal fun featuredFacts(pick: FeaturedPick, title: String, chat: String, s: com.tmplayer.i18n.Messages): String {
    val item = pick.item
    val series = pick.series
    val name = SeriesShelf.episodeOf(item)
    val year = name.year?.takeIf { series == null && it.toString() !in title }?.toString()
    val which = when {
        series != null -> series.seasons.maxOfOrNull { it.number }?.takeIf { it > 0 }?.let { s.seriesSeason(it) }
        item.versions.size > 1 -> s.detailVersions(item.versions.size)
        else -> null
    }
    return listOfNotNull(year, which, item.qualityTags.firstOrNull(), chat.ifBlank { null }).joinToString(SEPARATOR)
}

/** The carousel's frame while the starred chats are still being asked: the same size, shimmering. */
@Composable
private fun FeaturedSkeleton(start: Dp, end: Dp, wide: Boolean) {
    val shimmer = rememberShimmer()
    Box(Modifier.fillMaxSize()) {
        SkeletonBox(shimmer, Modifier.fillMaxSize(), RectangleShape)
        Box(Modifier.fillMaxSize().background(Scrims.bottomLong(SKELETON_FADE)))
        // On its side the stand-ins take the start, where the name and Play will be.
        if (wide) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(WIDE_WORDS_SHARE)
                    .padding(start = start, bottom = WIDE_FOOT),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBox(shimmer, Modifier.fillMaxWidth(0.7f).height(30.dp), RoundedCornerShape(8.dp))
                SkeletonBox(shimmer, Modifier.fillMaxWidth(0.5f).height(14.dp), RoundedCornerShape(7.dp))
                SkeletonBox(shimmer, Modifier.padding(top = 4.dp).size(width = 160.dp, height = HERO_PILL_HEIGHT), CircleShape)
            }
            return@Box
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = start, end = end, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.6f).height(30.dp), RoundedCornerShape(8.dp))
            SkeletonBox(shimmer, Modifier.padding(top = 4.dp).fillMaxWidth(0.7f).height(HERO_PILL_HEIGHT), CircleShape)
        }
    }
}

/**
 * How tall the carousel is in a window [width] wide and [height] tall (below the status bar):
 * width x 1.33, as a tall page's backdrop is, at most [VIEWPORT_SHARE] of the height so the first
 * row shows under it, and never so short that the name and Play have no room.
 *
 * On its side the words stand beside the picture rather than over it, so the carousel needs less
 * of the height: [WIDE_VIEWPORT_SHARE] of it, never under [WIDE_MIN_HEIGHT], so Continue watching
 * shows under it on a phone held sideways.
 */
internal fun featuredHeight(width: Dp, height: Dp): Dp =
    if (width > height) {
        (height * WIDE_VIEWPORT_SHARE).coerceAtLeast(min(WIDE_MIN_HEIGHT, height * 0.9f))
    } else {
        (width * 1.33f).coerceAtMost(height * VIEWPORT_SHARE).coerceAtLeast(min(MIN_HEIGHT, height * 0.9f))
    }

/**
 * Light status bar icons while the carousel's picture is under the status bar, whatever the
 * theme; the theme's own otherwise, and again when Home leaves.
 */
@Composable
internal fun StatusIconsOverArt(overArt: Boolean) {
    val dark = LocalDarkTheme.current
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(overArt, dark, window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark && !overArt
        onDispose { controller.isAppearanceLightStatusBars = !dark }
    }
}

/** Takes whatever sideways scroll and fling the carousel leaves over, so no pager outside it moves. */
private val KeepSideways = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)
}

/** How long a page is up before the carousel moves on. */
private const val ADVANCE_MS = 6_000

/** The most of the window under the status bar the carousel takes. */
private const val VIEWPORT_SHARE = 0.7f

private val MIN_HEIGHT = 320.dp

/** The most of the window under the status bar the carousel takes on its side. */
private const val WIDE_VIEWPORT_SHARE = 0.72f

/** The least the carousel takes on its side: the name, the facts, the overview and Play. */
private val WIDE_MIN_HEIGHT = 250.dp

/** How much wider than tall the frame is before the words go to the start, as [EdgeBackdrop] decides. */
private const val WIDE_RATIO = 1.4f

/** The share of the width the words take on its side, over the start's fade. */
private const val WIDE_WORDS_SHARE = 0.48f

/** A frame on its side this tall (a tablet lying down) shows three lines of the overview. */
private val TALL_WIDE = 400.dp

private val SKELETON_FADE = 160.dp

/** The dark fade under the status bar. */
private val TOP_SCRIM = 72.dp

/** Between a page's name and Play. */
private val TEXT_GAP = 10.dp

private const val SEPARATOR = "  ·  "

/** The most a title logo takes over the phone's carousel: about two lines of the name. */
private val LOGO_MAX_WIDTH = 260.dp
private val LOGO_MAX_HEIGHT = 84.dp

/** A logo on its side, where the height is short: about one line and a half of the name. */
private val WIDE_LOGO_MAX_HEIGHT = 64.dp
