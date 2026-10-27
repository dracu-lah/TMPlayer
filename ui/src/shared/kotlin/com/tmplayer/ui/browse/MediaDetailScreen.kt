package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.components.riseIn
import androidx.compose.foundation.background
import com.tmplayer.ui.theme.trackedFor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.tmplayer.ui.components.pressScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Bookmark
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.Messages
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.components.revealFromTop
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MetaState
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMetaExtrasState
import com.tmplayer.ui.online.rememberMetaState
import com.tmplayer.ui.online.rememberTitleLogoReady
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * A video's detail as a whole screen, in the manner of a streaming app's title page, opened by a
 * long press, a hold of OK or the info key on a tile.
 *
 * A wide screen (a television, a phone on its side) keeps the backdrop across the top end of the
 * screen, faded into the page on its start and foot with the long seven-stop curve, and writes the
 * title, the facts and the actions over the faded part. The backdrop stays where it is while the
 * page scrolls up over it to the cast, "More like this" and the file's facts, and dims as it goes.
 * A tall screen runs the backdrop across the top, under the status bar, with the title at its
 * foot, and the backdrop scrolls away at half the page's speed (pinned with animations off).
 *
 * The backdrop is the online picture when posters and overviews have one, else the Telegram
 * thumbnail as a soft field ([com.tmplayer.ui.components.ArtBackdrop]), else the video's
 * generated cover. Under the title, one line of facts with dots between (year, length, quality,
 * size, the chat), then one bright Play or Resume pill and the other actions as round buttons.
 *
 * Close sits at the top end on both; Back closes as well. On a phone the page leaves by the round
 * Back at the top start instead, and [topEndAction] (the Cast button) mirrors it at the top end;
 * elsewhere the action stands just before Close, in one row with it.
 *
 * Nothing on it moves once it is drawn (see DetailSlots.kt): the first frame waits a moment for
 * what the caches hold, and what is still on its way after that (the match's logo, year and
 * overview, the genres and scores, the trailer, the cast) has its place held at its size, with
 * soft placeholders, and fades in there. Play stays where it was first drawn, under a finger and
 * under the remote's focus.
 *
 * @param firstAction focused when the screen opens on a remote or a keyboard: Play or Resume.
 * @param topEndAction one button over the picture at the top end (Cast), drawn the page's way.
 */
@Composable
fun MediaDetailScreen(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    firstAction: FocusRequester? = null,
    onOpenItem: ((MediaItem) -> Unit)? = null,
    /** This video's bookmarks, under the actions; each opens the player at its moment. */
    bookmarks: List<Bookmark> = emptyList(),
    onOpenBookmark: ((Bookmark) -> Unit)? = null,
    /**
     * Another copy of the film chosen from its copies list ([Versions]); the platform remembers
     * the choice for the video and turns the page to it. [onOpenItem] when not given.
     */
    onPickCopy: ((MediaItem) -> Unit)? = onOpenItem,
    /** Whether a copy has a place saved, for the "Started" in its row. */
    copyStarted: (MediaItem) -> Boolean = { false },
    topEndAction: (@Composable () -> Unit)? = null,
) {
    val metaState = rememberMetaState(item)
    val meta = metaState.value
    val extrasState = rememberMetaExtrasState(meta, metaState.settled)
    val extras = extrasState.value
    // The first frame waits, a moment at most, for what the caches hold: a page seen before is
    // drawn whole at once, and one new to the device holds places for what is still coming.
    val logoReady = rememberTitleLogoReady(meta, metaState.settled)
    val firstPaint = rememberFirstPaint(item.id, metaState.settled && extrasState.settled && logoReady)
    val cover = rememberCover(item)
    // A record's item (opened from Continue watching, say) carries no thumbnail: its picture is
    // looked up, as the card it was opened from did.
    val art = rememberArtItem(item)
    val source = remember(item.id, art, cover, meta?.wideUrl) { BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, meta?.wideUrl) }
    val page = DetailPage(item, chatTitle, watched, finished, rows, meta, extras, source, firstAction, onOpenItem, bookmarks, onOpenBookmark, onPickCopy, copyStarted, metaState, extrasState)
    // Decided on the first frame and kept for the visit, the phone turned included.
    val holds = rememberHolds(page, firstPaint)
    BoxWithConstraints(modifier.fillMaxSize().background(Tone.background)) {
        val wide = maxWidth > maxHeight
        // Until the first frame only the page's own ground and its way out, while the caches are asked.
        if (firstPaint) {
            if (wide) CinemaLayout(page, holds, maxHeight) else StackedLayout(page, holds, maxWidth, maxHeight)
        }
        if (com.tmplayer.ui.components.usesFloatingChrome()) {
            // The page scrolls up under the status bar; a fade there keeps the clock and the
            // text beneath it from being drawn over each other once the backdrop has gone by.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Tone.background, Tone.background.copy(alpha = 0.6f)),
                        ),
                    ),
            )
            // A phone's pages all leave by the round Back at the top start, so this one does too.
            // A size up here, the page's picture behind it, and matched by the action opposite.
            com.tmplayer.ui.components.FloatingBackButton(
                TmIcons.ArrowBack,
                LocalStrings.current.commonBack,
                onClose,
                Modifier.align(Alignment.TopStart),
                size = com.tmplayer.ui.components.FloatingChrome.PageButton,
                iconSize = com.tmplayer.ui.components.FloatingChrome.PageButtonIcon,
            )
            topEndAction?.let { action ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(end = com.tmplayer.ui.components.FloatingChrome.Inset, top = 10.dp),
                ) { action() }
            }
        } else {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(if (wide && !isTouch()) 24.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                topEndAction?.invoke()
                CloseButton(onClose)
            }
        }
    }
}

/** Everything a layout of the detail page draws from, gathered once. */
private class DetailPage(
    val item: MediaItem,
    val chatTitle: String,
    val watched: WatchPoint?,
    val finished: Boolean,
    val rows: List<DetailRow>,
    val meta: MetaInfo?,
    val extras: MetaExtras?,
    val source: BackdropSource,
    val firstAction: FocusRequester?,
    val onOpenItem: ((MediaItem) -> Unit)?,
    val bookmarks: List<Bookmark>,
    val onOpenBookmark: ((Bookmark) -> Unit)?,
    val onPickCopy: ((MediaItem) -> Unit)?,
    val copyStarted: (MediaItem) -> Boolean,
    /** The match, and whether more may still come of it. */
    val metaState: MetaState<MetaInfo>,
    /** The extras, and whether more may still come of them (the scores, after a kept answer). */
    val extrasState: MetaState<MetaExtras>,
)

/**
 * The places a page holds from its first frame for what is still on its way, decided then and kept
 * for the visit: see DetailSlots.kt.
 */
private class Holds(
    /** The title area at the logo's height. */
    val title: Boolean,
    /** Two lines for the genres and the scores. */
    val metaLines: Boolean,
    /** The overview's lines, its placeholder while it comes. */
    val synopsis: Boolean,
    /** The trailer's button, unseen until it comes. */
    val trailer: Boolean,
    /** The match's year on the facts line (known on the first frame), else on the genres line. */
    val yearInFacts: Boolean,
    /** Episodes at the start of the buttons (known on the first frame), else at their end. */
    val episodesFirst: Boolean,
    /** A tall page's backdrop at the online picture's height. */
    val onlineBackdrop: Boolean,
    /** The cast row's placeholder below the first screen. */
    val cast: Boolean,
)

@Composable
private fun rememberHolds(page: DetailPage, firstPaint: Boolean): Holds {
    val titleHold = logoMayCome(page.metaState)
    val episode = remember(page.item.id) { SeriesShelf.episodeOf(page.item).episode != null }
    return remember(page.item.id, firstPaint) {
        val metaComing = !page.metaState.settled
        val extrasComing = !page.extrasState.settled
        Holds(
            title = titleHold,
            metaLines = extrasComing,
            synopsis = metaComing,
            trailer = page.extras?.trailer != null || extrasComing,
            yearInFacts = !metaComing,
            episodesFirst = episode || page.meta?.kind == MetaKind.Show,
            onlineBackdrop = page.meta?.wideUrl != null || metaComing,
            cast = extrasComing,
        )
    }
}

@Composable
private fun CinemaLayout(page: DetailPage, holds: Holds, height: Dp) {
    val tv = !isTouch()
    val scroll = rememberScrollState()
    val episodes = remember { BringIntoViewRequester() }
    val side = if (tv) 48.dp else 24.dp
    // How far the page has to scroll before the picture is as dim as it gets.
    val dimOver = with(LocalDensity.current) { (height * 0.6f).toPx() }
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(
            page.source,
            fromStart = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(BACKDROP_SHARE)
                .fillMaxHeight(0.85f)
                .graphicsLayer { alpha = 1f - 0.75f * (scroll.value / dimOver).coerceIn(0f, 1f) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = side),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // The title block fills the first screen and sits at its foot, under the picture's
            // fade, so the picture has the top of the screen to itself. Focus landing on any of
            // its buttons, coming back up from below the fold, shows all of it again: the
            // picture and the title, not only the button at its foot.
            Column(
                Modifier.fillMaxWidth().heightIn(min = height * 0.9f).revealFromTop(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
            ) {
                Spacer(Modifier.height(if (tv) 24.dp else 12.dp))
                // The words keep to the faded half; the buttons may run on under the picture.
                Column(Modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.riseIn(0, enabled = !tv)) { TitleBlock(page, holds) }
                    Box(Modifier.riseIn(1, enabled = !tv)) { FactsBlock(page, holds) }
                    SynopsisSlot(
                        synopsisOf(page),
                        waiting = !page.metaState.settled,
                        hold = holds.synopsis,
                        style = MaterialTheme.typography.bodyLarge,
                        lines = if (tv) 3 else 2,
                    )
                    ProtectedNote(page.item)
                }
                Column(Modifier.riseIn(2, enabled = !tv), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // A television keeps the everyday buttons beside Play and the rest at the foot
                    // of the page, as a phone does; Right walks along the first, Down reaches the rest.
                    Actions(page, holds, onEpisodes = episodesButton(page.item, page.meta, episodes), headlineOnly = tv)
                    Versions(page.item, page.onPickCopy, page.chatTitle, page.copyStarted)
                    TelegramQualityLine(page.item)
                }
                Spacer(Modifier.height(if (tv) 8.dp else 4.dp))
            }
            // Just under the first screen, so a long list never pushes the title off the top.
            Box(Modifier.widthIn(max = 720.dp)) { DetailBookmarks(page.bookmarks, page.onOpenBookmark) }
            BelowTheFold(page, holds, Modifier.bringIntoViewRequester(episodes))
            if (tv) MoreActions(page)
            Spacer(Modifier.height(24.dp))
        }
    }
    // Focus on the first action shows the first screen whole (see revealFromTop); the return to
    // the top once it has settled stays as a guard for a picture or a title that arrives late
    // and changes the height under it. Back from a video opened from this page leaves both to the
    // tile it was opened by, which takes the focus and shows its row (see DetailBackStack).
    val back = LocalDetailReturn.current
    LaunchedEffect(page.item.id) {
        if (back.tookFocus()) return@LaunchedEffect
        runCatching { page.firstAction?.requestFocus() }
        kotlinx.coroutines.delay(FOCUS_SETTLE_MS)
        scroll.scrollTo(0)
    }
}

@Composable
private fun StackedLayout(page: DetailPage, holds: Holds, width: Dp, height: Dp) {
    val scroll = rememberScrollState()
    val episodes = remember { BringIntoViewRequester() }
    val reduced = rememberReducedMotion()
    val touch = isTouch()
    // The frame's height is chosen once: an online picture arriving fades into it rather than
    // making the frame taller and pushing the title, the facts and Play down.
    val backdrop = tallBackdropHeight(width, height, online = holds.onlineBackdrop)
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        // Under the status bar and the full width; the title sits on its foot, in the fade.
        Box(Modifier.fillMaxWidth().heightIn(min = backdrop).clipToBounds()) {
            PageBackdrop(
                page.source,
                modifier = Modifier
                    .matchParentSize()
                    // Half the page's speed, so the picture drifts away under the title rather
                    // than leaving with it.
                    .graphicsLayer { if (!reduced) translationY = scroll.value * PARALLAX },
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, end = 16.dp, top = backdrop * 0.4f)
                    .riseIn(0, enabled = touch),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitleBlock(page, holds)
            }
        }
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            // Back up on the buttons from below with a remote or keys, the title above them shows too.
            // The page arrives in reading order: the title, then the facts, Play and its buttons,
            // the copies line and the overview, each a beat after the one above.
            Column(Modifier.revealFromTop(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.riseIn(1, enabled = touch)) { FactsBlock(page, holds) }
                Box(Modifier.riseIn(2, enabled = touch)) {
                    Actions(page, holds, primaryFullWidth = true, onEpisodes = episodesButton(page.item, page.meta, episodes), headlineOnly = true)
                }
                Box(Modifier.riseIn(3, enabled = touch)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Versions(page.item, page.onPickCopy, page.chatTitle, page.copyStarted)
                        TelegramQualityLine(page.item)
                    }
                }
                if (synopsisOf(page) != null || holds.synopsis) {
                    Box(Modifier.riseIn(4, enabled = touch)) {
                        SynopsisSlot(synopsisOf(page), waiting = !page.metaState.settled, hold = holds.synopsis)
                    }
                }
                ProtectedNote(page.item)
                DetailBookmarks(page.bookmarks, page.onOpenBookmark)
            }
            BelowTheFold(page, holds, Modifier.bringIntoViewRequester(episodes))
            MoreActions(page)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The episode or part over the title in the accent, then the title in the page's display size. */
@Composable
private fun TitleBlock(page: DetailPage, holds: Holds) {
    val item = page.item
    val meta = page.meta
    val parsed = remember(item) { item.parsedName }
    // A match's own title, in the UI language; else the name as read, its joiners made spaces.
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(JOINERS, " ") }
    val eyebrow = listOfNotNull(
        parsed.episodeCode,
        meta?.episode?.name?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (eyebrow.isNotEmpty()) {
            // One line, so the episode's name arriving after its code never adds a second.
            Text(eyebrow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tone.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The film's or show's title logo from TMDB where it has one, read out as the name, in a
        // place as tall as the logo may be, so the name giving way to it moves nothing.
        val tv = !isTouch()
        val logoHeight = if (tv) TITLE_LOGO_HEIGHT_TV else TITLE_LOGO_HEIGHT
        TitleSlot(logoHeight, hold = holds.title) {
            com.tmplayer.ui.online.TitleArt(
                meta,
                maxWidth = if (tv) TITLE_LOGO_WIDTH_TV else TITLE_LOGO_WIDTH,
                maxHeight = logoHeight,
                settled = page.metaState.settled,
                waitMs = 0L,
                modifier = Modifier.clearAndSetSemantics {
                    heading()
                    contentDescription = heading
                },
            ) {
                Text(
                    heading,
                    style = MaterialTheme.typography.displaySmall.trackedFor(heading),
                    color = Tone.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The facts line, the provider's genres and people under it, and where the viewer stopped. */
@Composable
private fun FactsBlock(page: DetailPage, holds: Holds) {
    val s = LocalStrings.current
    val parsedYear = remember(page.item) { page.item.parsedName.year }
    // The match's year joins the facts line only when it was there on the first frame; one that
    // comes later opens the genres line instead, in its held place, so the facts never re-wrap.
    val matchYear = page.meta?.year.takeIf { parsedYear == null }
    val factsYear = parsedYear ?: matchYear.takeIf { holds.yearInFacts }
    val facts = remember(page.item, factsYear, page.chatTitle, page.finished, s) { videoFacts(page, factsYear, s) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FactLine(facts)
        DetailMetaLines(
            page.extrasState,
            year = matchYear.takeIf { !holds.yearInFacts },
            hold = holds.metaLines,
            yearPending = !holds.yearInFacts && parsedYear == null && !page.metaState.settled,
            // The file's own length is on the facts line already.
            runtime = page.item.durationSec <= 0,
        )
        ResumeLine(page.watched, page.finished, page.item.durationSec)
        PiecesMissingLine(page.item)
    }
}

/** Year, length, quality, size and the chat, then Watched; only what the file and the match know. */
private fun videoFacts(page: DetailPage, year: Int?, s: Messages): List<Fact> {
    val item = page.item
    val facts = MediaFacts.of(item)
    return listOfNotNull(
        year?.let { Fact(it.toString()) },
        item.durationSec.takeIf { it > 0 }?.let { Fact(s.formatter.duration(it.toLong())) },
        // As the copy under Play names it, so the badge and the copy line never disagree.
        (com.tmplayer.data.VideoCopies.qualityLabel(item) ?: facts.resolution)?.let { Fact(it, chip = true) },
        item.sizeBytes.takeIf { it > 0 }?.let { Fact(com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(it))) },
        page.chatTitle.takeIf { it.isNotBlank() }?.let { Fact(it) },
        Fact(s.detailWatchedLine, accent = true).takeIf { page.finished },
    )
}

/** The episode's or the film's overview when online metadata has one, else the caption. */
private fun synopsisOf(page: DetailPage): String? {
    val meta = page.meta
    return meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
        ?: com.tmplayer.data.MediaText.overviewOf(page.item)
}

/** "This chat does not allow saving": said once, quietly, where the download would be. */
@Composable
private fun ProtectedNote(item: MediaItem) {
    if (item.canBeSaved) return
    Text(
        LocalStrings.current.detailProtected,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.Small))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Play or Resume as the one bright pill, then every other action as a round button: the platform's
 * own lines in their order, Episodes beside the pill for an episode, the trailer, and the switch
 * for posters and overviews while they are off. On a television the pill and the others share one
 * line that wraps, Play first, so Right walks along them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    page: DetailPage,
    holds: Holds,
    primaryFullWidth: Boolean = false,
    /** Scrolls down to the seasons and episodes; null where the video is not an episode. */
    onEpisodes: (() -> Unit)? = null,
    /**
     * A phone's page: only the everyday buttons (watched, watch later, download, the trailer, the
     * episodes) under Play, centred; the rest wait at the foot of the page ([MoreActions]).
     */
    headlineOnly: Boolean = false,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val trailers = rememberTrailerLauncher()
    // The trailer's player, once for the page and outside its list: placed in the list's first
    // item it was composed twice while the first paint's list handed over to the live one, and
    // two players opened one over the other (sound over a black screen, 2026-10-22).
    TrailerHost(trailers)
    // Posters and overviews are off: one button to turn them on from here, as Settings can.
    val online = OnlineMetadata.current
    val metadataOff = online != null && !online.settings.collectAsState().value.enabled
    val trailer = page.extras?.trailer
    val primary = page.rows.firstOrNull()
    val others = buildList {
        addAll(page.rows.drop(1).filter { !headlineOnly || it.action in HEADLINE_ACTIONS })
        // Beside the main button, as a streaming app's "Episodes" sits by Play; at the end when the
        // match only said it is a show after the first frame, so nothing already drawn moves.
        if (onEpisodes != null) {
            val row = DetailRow(DetailAction.Play, s.detailEpisodes, TmIcons.Episodes, onSelect = onEpisodes)
            if (holds.episodesFirst) add(0, row) else add(row)
        }
        // The trailer's place is held while the extras are on their way, unseen; it fades in there.
        if (trailer != null || holds.trailer) {
            add(DetailRow(DetailAction.Play, s.metadataTrailer, TrailerIcon, onSelect = { trailer?.let(trailers::open) }, placeholder = trailer == null))
        }
        if (!headlineOnly && online != null && page.meta == null && metadataOff) {
            add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online.setEnabled(true) }))
        }
    }
    // The time left is on the line over the buttons (see ResumeLine), so the pill says only Resume.
    val hero: @Composable (Modifier) -> Unit = { modifier ->
        if (primary != null) HeroPill(primary.label, primary.icon, primary.onSelect, modifier, focusRequester = page.firstAction)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tv) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                hero(Modifier)
                others.forEach { androidx.compose.runtime.key(slotOf(it)) { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), toggles = togglesOn(it.action), progress = it.progress, enabled = it.enabled, ghost = it.placeholder, waiting = it.placeholder && !page.extrasState.settled) } }
            }
        } else if (!primaryFullWidth) {
            // A phone on its side: the round buttons in Play's row, after it, wrapping only when
            // they do not fit, rather than a row of their own under a short Play. Play comes
            // first at the start, so a trailer arriving later never moves it.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hero(Modifier)
                others.forEach { androidx.compose.runtime.key(slotOf(it)) { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), toggles = togglesOn(it.action), progress = it.progress, enabled = it.enabled, ghost = it.placeholder, waiting = it.placeholder && !page.extrasState.settled) } }
            }
        } else {
            hero(Modifier.fillMaxWidth())
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, if (headlineOnly) Alignment.CenterHorizontally else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                others.forEach { androidx.compose.runtime.key(slotOf(it)) { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), toggles = togglesOn(it.action), progress = it.progress, enabled = it.enabled, ghost = it.placeholder, waiting = it.placeholder && !page.extrasState.settled) } }
            }
        }
        trailers.unopened?.let { TrailerQr(it, Modifier.padding(vertical = 8.dp)) }
    }
}

/**
 * Which button [row] is, across its turns: Mark watched and Mark as unwatched are one button, as
 * are the two Watch later lines and Download with what it becomes (its ring, Cancel, Downloaded).
 * The buttons are keyed by it, so a remote's focus stays on the button pressed when the row
 * changes around it (Mark watched also takes Start over and Watch later away); placed by position,
 * focus stayed where the pressed button had been, on whichever button moved into its place.
 */
private fun slotOf(row: DetailRow): Any = when (row.action) {
    DetailAction.MarkWatched, DetailAction.MarkUnwatched -> DetailAction.MarkWatched
    DetailAction.AddWatchLater, DetailAction.RemoveWatchLater -> DetailAction.AddWatchLater
    DetailAction.Download, DetailAction.SaveToDownloads, DetailAction.InDownloads, DetailAction.CancelDownload -> DetailAction.Download
    // Episodes, the trailer and the posters switch all borrow Play; their names tell them apart.
    DetailAction.Play -> row.label
    else -> row.action
}

/** Whether [action] turns something on (true), off (false), or is not a switch (null). */
private fun togglesOn(action: DetailAction): Boolean? = when (action) {
    DetailAction.MarkWatched, DetailAction.AddWatchLater -> true
    DetailAction.MarkUnwatched, DetailAction.RemoveWatchLater -> false
    else -> null
}

/** The everyday buttons a phone keeps under Play; see [Actions]' headlineOnly and [MoreActions]. */
internal val HEADLINE_ACTIONS = setOf(
    DetailAction.MarkWatched,
    DetailAction.MarkUnwatched,
    DetailAction.AddWatchLater,
    DetailAction.RemoveWatchLater,
    DetailAction.Download,
    DetailAction.SaveToDownloads,
    DetailAction.InDownloads,
    DetailAction.CancelDownload,
)

/**
 * The rest of a phone page's buttons, at its foot and centred: Start over, Remove download, Share,
 * Open elsewhere, the chat, the corrections (Not the same video, Wrong match, Rename) and the
 * switch for posters while they are off. Things done now and then, out of the way of Play.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreActions(page: DetailPage) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current
    val metadataOff = online != null && !online.settings.collectAsState().value.enabled
    val rest = buildList {
        addAll(page.rows.drop(1).filter { it.action !in HEADLINE_ACTIONS })
        if (online != null && page.meta == null && metadataOff) {
            add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online.setEnabled(true) }))
        }
    }
    if (rest.isEmpty()) return
    val tv = !isTouch()
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        // On a television these start where the title, Play and the facts start: centred, they
        // were the one block on the page standing in the middle of it.
        horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 4.dp, if (tv) Alignment.Start else Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
    ) {
        rest.forEach { androidx.compose.runtime.key(slotOf(it)) { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), toggles = togglesOn(it.action), progress = it.progress, enabled = it.enabled) } }
    }
}

/** The cast, "More like this", then the file's facts, below the first screen. */
@Composable
private fun BelowTheFold(
    page: DetailPage,
    holds: Holds,
    episodesModifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val item = page.item
    val meta = page.meta
    val extras = page.extras
    val onOpenItem = page.onOpenItem
    val facts = remember(item) { MediaFacts.of(item) }
    // The cast first, a short row; then the seasons and episodes, which can run long, so they
    // never push the cast out of sight; then "More like this". While the extras are on their way
    // the cast's place shows soft faces.
    when {
        meta != null && extras != null -> MetaCastRow(extras.cast)
        holds.cast && !page.extrasState.settled -> com.tmplayer.ui.online.MetaCastPlaceholder()
    }
    SeasonsSection(item, meta, extras, onOpenItem, episodesModifier)
    if (meta != null && extras != null) MoreLikeThisRow(meta, extras, onOpenItem)
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.detailAbout, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        // Who made it, here with the credits rather than over Play, where it arrived late.
        MetaPeopleLine(extras)
        DetailFacts(item, facts, s, overview = null, showSynopsis = false)
        meta?.let { MetaCredit(it) }
    }
}

/** Close, at the top end, on a dark disc so it reads over any picture. */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    PageDiscButton(TmIcons.Close, LocalStrings.current.commonClose, onClose, modifier)
}

/**
 * A page's own close or back control, on a dark disc so it reads over any backdrop: the detail
 * page's Close and a show page's Back.
 */
@Composable
fun PageDiscButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** On (Cast while a TV is connected): filled in the hero colour. */
    active: Boolean = false,
    /** The detail page's own pair over its picture, a size up on a phone ([FloatingChrome.PageButton]). */
    prominent: Boolean = false,
) {
    if (com.tmplayer.ui.components.usesFloatingChrome()) {
        // On a phone, the same round floating button every other page's Back is.
        val big = prominent
        com.tmplayer.ui.components.FloatingRoundButton(
            icon, label, onClick, modifier,
            size = if (big) com.tmplayer.ui.components.FloatingChrome.PageButton else com.tmplayer.ui.components.FloatingChrome.Button,
            iconSize = if (big) com.tmplayer.ui.components.FloatingChrome.PageButtonIcon else com.tmplayer.ui.components.FloatingChrome.ButtonIcon,
            active = active,
        )
        return
    }
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val fill = when {
        focused -> Tone.focusFill
        active -> Tone.hero
        else -> Color.Black.copy(alpha = 0.55f)
    }
    val tint = when {
        focused -> Tone.onFocusFill
        active -> Tone.onHero
        else -> Color.White
    }
    // Its name under it: on a hover on the desktop, while the remote is on it on a television.
    // The modifier on a Box of its own, so an align() given to it places the button (see FloatingRoundButton).
    Box(modifier) {
        com.tmplayer.ui.components.IconTip(label, side = com.tmplayer.ui.components.TipSide.Below) {
            Box(
                Modifier
                    .pressScale(interactions)
                    // A full touch target on a tablet too, as on a television.
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                    .semantics {
                        contentDescription = label
                        if (active) selected = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** The Episodes button's action for an episode of a show, or null for anything else. */
@Composable
internal fun episodesButton(item: MediaItem, meta: MetaInfo?, target: BringIntoViewRequester): (() -> Unit)? {
    val episode = remember(item.id) { SeriesShelf.episodeOf(item).episode != null }
    if (!episode && meta?.kind != MetaKind.Show) return null
    val scope = rememberCoroutineScope()
    return { scope.launch { target.bringIntoView() } }
}

private const val FOCUS_SETTLE_MS = 120L
private val JOINERS = Regex("[-_.]+")
