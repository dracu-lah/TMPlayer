package com.tmplayer.ui.browse

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Bookmark
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WatchPoint
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.IconTip
import com.tmplayer.ui.components.SwapIcon
import com.tmplayer.ui.components.TipSide
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMetaExtrasState
import com.tmplayer.ui.online.rememberMetaState
import com.tmplayer.ui.online.rememberTitleLogoReady
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.theme.trackedFor

/*
 * A video's detail drawn for a large window: what the desktop's sheet (and a wide browser
 * window, which draws the desktop's screens) shows over the page. The phone keeps its own page
 * ([MediaDetailScreen]) and the television its pane ([MediaDetailPanel]).
 *
 * The order is a title page's: the backdrop across the top with the title logo (or the name, set
 * large) on its faded foot, the facts and the scores, Play or Resume as the one bright pill with
 * the everyday actions as round buttons beside it, then the overview at a reading measure beside
 * the file's own facts, the copies and bookmarks, the cast, the seasons and episodes, "More like
 * this", and the things done now and then at the foot.
 *
 * As on the phone's page ([MediaDetailScreen]), nothing moves once drawn: the first frame waits a
 * moment for the caches, and what is still coming has its place held (DetailSlots.kt).
 */

/** A video's detail for a large window; see the file's note. Same inputs as [MediaDetailPanel]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WideDetailPanel(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    firstAction: FocusRequester? = null,
    onOpenItem: ((MediaItem) -> Unit)? = null,
    bookmarks: List<Bookmark> = emptyList(),
    onOpenBookmark: ((Bookmark) -> Unit)? = null,
    onPickCopy: ((MediaItem) -> Unit)? = onOpenItem,
    copyStarted: (MediaItem) -> Boolean = { false },
    /** How far the page's words sit in from the window's sides; null picks one for the width. */
    margin: androidx.compose.ui.unit.Dp? = null,
) {
    val s = LocalStrings.current
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val parsed = remember(item, corrections) { com.tmplayer.data.SeriesShelf.episodeOf(item, corrections) }
    val metaState = rememberMetaState(item)
    val meta = metaState.value
    // A match's own title, in the UI language; else the name as read, its joiners made spaces.
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(WIDE_JOINERS, " ") }
    val eyebrow = listOfNotNull(parsed.episodeCode, meta?.episode?.name?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    val metaOverview = meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
    val overview = metaOverview ?: remember(item) { com.tmplayer.data.MediaText.overviewOf(item) }
    val extrasState = rememberMetaExtrasState(meta, metaState.settled)
    val extras = extrasState.value
    val logoReady = rememberTitleLogoReady(meta, metaState.settled)
    val firstPaint = rememberFirstPaint(item.id, metaState.settled && extrasState.settled && logoReady)
    val trailers = rememberTrailerLauncher()
    // The trailer's player, once for the page and outside its list: placed in the list's first
    // item it was composed twice while the first paint's list handed over to the live one, and
    // two players opened one over the other (sound over a black screen, 2026-10-22).
    TrailerHost(trailers)
    val cover = rememberCover(item)
    val art = rememberArtItem(item)
    val source = remember(item.id, art, cover, meta?.wideUrl, meta?.posterUrl) { BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, meta?.wideUrl, meta?.posterUrl) }
    val facts = remember(item) { MediaFacts.of(item) }
    val online = OnlineMetadata.current
    val lookupsOn = online?.settings?.collectAsState()?.value?.enabled == true
    val metadataOff = online != null && !lookupsOn
    val scroll = rememberScrollState()
    val reduced = rememberReducedMotion()
    val episodesAt = remember { BringIntoViewRequester() }
    val onEpisodes = episodesButton(item, meta, episodesAt)

    val primary = rows.firstOrNull()
    // What the page holds places for, decided on its first frame (see DetailSlots.kt).
    val titleHold = logoMayCome(metaState)
    val holdsTitle = remember(item.id, firstPaint) { titleHold }
    val metaComing = !metaState.settled
    val extrasComing = !extrasState.settled
    val holdMetaLines = remember(item.id, firstPaint) { extrasComing }
    val holdSynopsis = remember(item.id, firstPaint) { metaComing }
    val holdTrailer = remember(item.id, firstPaint) { extras?.trailer != null || extrasComing }
    val holdCast = remember(item.id, firstPaint) { extrasComing }
    val yearInFacts = remember(item.id, firstPaint) { !metaComing }
    val episodesFirst = remember(item.id, firstPaint) { parsed.episode != null || meta?.kind == com.tmplayer.online.MetaKind.Show }
    // Beside Play: the episodes, the everyday switches and the trailer, whose place is held
    // (unseen) while the extras are on their way.
    val quick = buildList {
        addAll(rows.drop(1).filter { it.action in WIDE_HEADLINE })
        if (onEpisodes != null) {
            val row = DetailRow(DetailAction.Play, s.detailEpisodes, TmIcons.Episodes, onSelect = onEpisodes)
            if (episodesFirst) add(0, row) else add(row)
        }
        val trailer = extras?.trailer
        if (trailer != null || holdTrailer) {
            add(DetailRow(DetailAction.Play, s.metadataTrailer, TrailerIcon, onSelect = { trailer?.let(trailers::open) }, placeholder = trailer == null))
        }
    }
    // At the foot: the chat, the corrections, the hand offs, the switch for posters while off.
    val rest = buildList {
        addAll(rows.drop(1).filter { it.action !in WIDE_HEADLINE })
        if (metadataOff) add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online?.setEnabled(true) }))
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val roomy = maxWidth >= ROOMY
        // The platform's margin for a title page (the desktop's is twice a page's own), else one
        // drawn to the window: the hero's words and the sections under it start on one line.
        val side = margin ?: if (roomy) 56.dp else 36.dp
        // The hero holds the title, the facts and the actions over the picture (user, 2026-11-01:
        // its band used to hold the title alone, with everything else squeezed in under it). It
        // is at least this tall, and taller where its words need it.
        val heroHeight = (maxHeight * HERO_SHARE).coerceIn(HERO_MIN, HERO_MAX)
        // A short window keeps the title logo smaller, so Play stays on the first screen.
        val logoHeight = if (roomy && maxHeight >= TALL_WINDOW) 150.dp else 120.dp
        // The sheet's own ground and Close, for the moment the caches are asked.
        if (firstPaint) Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().heightIn(min = heroHeight).clipToBounds()) {
                // Half the page's speed, so the picture drifts away under the title.
                val drift = Modifier.matchParentSize().graphicsLayer { if (!reduced) translationY = scroll.value * PARALLAX }
                // The same full-bleed hero as the Home carousel ([com.tmplayer.ui.components.EdgeBackdrop]):
                // almost any desktop window is wider than 16:9 at this height, so a plain crop
                // across the middle used to show only a slice of the picture; EdgeBackdrop instead
                // stands the picture whole at its own aspect, cropped from the foot only where that
                // runs taller than the frame, with the same long start fade as the carousel.
                com.tmplayer.ui.components.EdgeBackdrop(source, drift)
                // Laid from the hero's foot up, so what is held above Play (the logo, the genres
                // line) never moves it; kept clear of the Back button at the top.
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = side, end = side, top = HERO_TOP, bottom = 12.dp)
                        .widthIn(max = HERO_TEXT),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (eyebrow.isNotEmpty()) {
                        // One line, so the episode's name arriving after its code never adds a second.
                        Text(eyebrow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tone.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TitleSlot(logoHeight, hold = holdsTitle) {
                        com.tmplayer.ui.online.TitleArt(
                            meta,
                            maxWidth = if (roomy) 460.dp else 380.dp,
                            maxHeight = logoHeight,
                            settled = metaState.settled,
                            skeletonHeight = logoHeight * 0.62f,
                            modifier = Modifier.clearAndSetSemantics {
                                heading()
                                contentDescription = heading
                            },
                        ) {
                            val style = if (roomy) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall
                            Text(heading, style = style.trackedFor(heading), fontWeight = FontWeight.Bold, color = Tone.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The match's year on the facts line only when it was there on the first
                        // frame; later it opens the genres line, in its held place.
                        val matchYear = meta?.year.takeIf { parsed.year == null }
                        FactLine(
                            listOfNotNull(
                                (parsed.year ?: matchYear.takeIf { yearInFacts })?.let { Fact(it.toString()) },
                                item.durationSec.takeIf { it > 0 }?.let { Fact(s.formatter.duration(it.toLong())) },
                                // As the copy under Play names it ([VideoCopies.qualityLabel]).
                                (com.tmplayer.data.VideoCopies.qualityLabel(item) ?: facts.resolution)?.let { Fact(it, chip = true) },
                                item.sizeBytes.takeIf { it > 0 }?.let { Fact(com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(it))) },
                                chatTitle.takeIf { it.isNotBlank() }?.let { Fact(it) },
                                Fact(s.detailWatchedLine, accent = true).takeIf { finished },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        DetailMetaLines(
                            extrasState,
                            year = matchYear.takeIf { !yearInFacts },
                            hold = holdMetaLines,
                            yearPending = !yearInFacts && parsed.year == null && metaComing,
                            // The file's own length is on the facts line already.
                            runtime = item.durationSec <= 0,
                        )
                        ResumeLine(watched, finished, item.durationSec)
                        PiecesMissingLine(item)
                    }
                    if (!item.canBeSaved) {
                        Text(
                            s.detailProtected,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.Small))
                                .background(Tone.surfaceHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    // Play and the round buttons share one top line: the pill is as tall as a
                    // button's circle, so a word under one button running to two lines never
                    // lowers that circle against the others.
                    Row(
                        Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        primary?.let { HeroPill(it.label, it.icon, it.onSelect, focusRequester = firstAction, large = true) }
                        FlowRow(
                            Modifier.weight(1f, fill = false),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            quick.forEach {
                                WideIconAction(
                                    it.label,
                                    it.icon,
                                    it.onSelect,
                                    destructive = it.destructive,
                                    progress = it.progress,
                                    enabled = it.enabled,
                                    ghost = it.placeholder,
                                    waiting = it.placeholder && extrasComing,
                                    short = roundLabel(it, s),
                                )
                            }
                        }
                    }
                    trailers.unopened?.let { TrailerQr(it, Modifier.padding(vertical = 4.dp)) }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = side, end = side, top = 24.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                // The overview at a reading measure with the copies and bookmarks under it, and
                // the file's own facts beside it where the window is wide enough, else under it.
                StoryBesideFacts(
                    story = {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (overview != null || holdSynopsis) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SynopsisSlot(overview, waiting = metaComing, hold = holdSynopsis, style = MaterialTheme.typography.bodyLarge, lines = SYNOPSIS_LINES)
                                    if (metaOverview != null && meta != null) MetaCredit(meta)
                                    MetaPeopleLine(extras)
                                }
                            } else {
                                MetaPeopleLine(extras)
                            }
                            Versions(item, onPickCopy, chatTitle, copyStarted)
                            TelegramQualityLine(item)
                            DetailBookmarks(bookmarks, onOpenBookmark)
                        }
                    },
                    facts = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.detailHeading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tone.text)
                            DetailFacts(item, facts, s, overview = null, showSynopsis = false, large = roomy)
                        }
                    },
                )

                when {
                    meta != null && extras != null -> MetaCastRow(extras.cast)
                    holdCast && extrasComing -> com.tmplayer.ui.online.MetaCastPlaceholder()
                }
                SeasonsSection(item, meta, extras, onOpenItem, Modifier.bringIntoViewRequester(episodesAt))
                if (meta != null && extras != null) MoreLikeThisRow(meta, extras, onOpenItem)

                if (rest.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        HorizontalDivider(color = Tone.outline)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            rest.forEach { WideTextAction(it.label, it.icon, it.onSelect, destructive = it.destructive) }
                        }
                    }
                }
                // Where the name came from: the provider's match and what backed it, or the file.
                if (lookupsOn) {
                    Text(
                        meta?.let { m -> m.year?.let { s.detailMatchedAs(m.title, it.toString()) } ?: s.detailMatchedAsNoYear(m.title) } ?: s.detailFromFileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Tone.muted,
                    )
                }
            }
        }
        PageDiscButton(TmIcons.ArrowBack, s.commonBack, onClose, Modifier.align(Alignment.TopStart).padding(16.dp))
    }
    // Play is composed once the first frame is drawn, which the caches may hold back a moment:
    // focus is asked for again then, so a held page still opens on Play.
    // Back from a video opened from this page leaves the focus to the tile it was opened by.
    val back = LocalDetailReturn.current
    androidx.compose.runtime.LaunchedEffect(item.id, firstPaint) {
        if (!firstPaint || firstAction == null) return@LaunchedEffect
        if (back.tookFocus()) return@LaunchedEffect
        repeat(FOCUS_AFTER_HOLD_TRIES) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { firstAction.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** Frames Play is asked for focus over once a held page is drawn. */
private const val FOCUS_AFTER_HOLD_TRIES = 10

/**
 * One of the everyday actions beside Play on a large window: a round button as tall as the pill,
 * outlined, with its short word under it, as the phone's own [RoundAction] carries (a row of bare
 * icons here, told apart only by hovering, read "off" beside the one bright pill, user 2026-11-01).
 * [label] stays the hover tip and what a screen reader reads; a switch's icon turns into its other
 * one in place, so the press shows.
 */
@Composable
internal fun WideIconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    /** A place held for a button still on its way (the trailer): laid out, unseen, unreachable; it fades in there. */
    ghost: Boolean = false,
    /** With [ghost]: still on its way, so a soft placeholder of the button stands in its place. */
    waiting: Boolean = false,
    /** A download under way, 0 to 1, for the ring round the button; see [RoundAction]. */
    progress: Float? = null,
    /** False for a button that only says how things stand (Downloaded): dimmed, a press does nothing. */
    enabled: Boolean = true,
    /** The word or two under the button, as [RoundAction]'s own [roundLabel]; falls back to [label]. */
    short: String = label,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shown by androidx.compose.animation.core.animateFloatAsState(if (ghost) 0f else 1f, com.tmplayer.ui.theme.Motion.standard(), label = "wideActionShown")
    if (ghost) {
        Column(Modifier.width(RoundActionWidth).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(WIDE_ACTION)) {
                if (waiting) com.tmplayer.ui.components.SkeletonBox(com.tmplayer.ui.components.rememberShimmer(), Modifier.matchParentSize(), CircleShape)
            }
        }
        return
    }
    val ink = when {
        focused -> Tone.onFocusFill
        !enabled -> Tone.muted
        destructive -> Tone.danger
        else -> Tone.text
    }
    Column(
        Modifier.graphicsLayer { alpha = shown }.width(RoundActionWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconTip(label, side = TipSide.Below) {
            Box(
                Modifier
                    .pressScale(interactions)
                    .size(WIDE_ACTION)
                    .downloadRing(progress)
                    .clip(CircleShape)
                    .background(if (focused) (if (destructive) Danger else Tone.focusFill) else Tone.surfaceHigh)
                    .border(1.dp, if (focused) Color.Transparent else Tone.outline, CircleShape)
                    .clickable(interactionSource = interactions, indication = if (enabled) LocalIndication.current else null, onClick = if (enabled) onClick else ({}))
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        if (!enabled) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                SwapIcon(icon, ink, size = 24.dp)
            }
        }
        Text(
            short,
            style = fitLongestWord(short, MaterialTheme.typography.labelSmall, RoundActionWidth),
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** One of the things done now and then, at the foot of the large detail: an outlined pill with its full name. */
@Composable
private fun WideTextAction(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = when {
        focused -> Tone.onFocusFill
        destructive -> Tone.danger
        else -> Tone.text
    }
    Row(
        Modifier
            .pressScale(interactions, com.tmplayer.ui.components.Micro.PillPressScale)
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (focused) (if (destructive) Danger else Tone.focusFill) else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else Tone.outline, CircleShape)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (focused || destructive) ink else Tone.muted, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * The actions a large window keeps beside Play: the phone's everyday ones ([HEADLINE_ACTIONS])
 * and Start over, which sits by Resume as a streaming app's does.
 */
private val WIDE_HEADLINE = HEADLINE_ACTIONS + DetailAction.StartOver

/** From this width the detail sets its words larger. */
private val ROOMY = 880.dp

/** About 70 characters of the overview's type: the measure it reads best at. */
private val READING = 660.dp

/** The narrowest the overview's column gets with the file's facts beside it. */
private val READING_MIN = 460.dp

/** The file's facts beside the overview: never narrower, so a release name wraps once at most. */
private val FACTS_MIN = 380.dp

/** The file's facts column stops growing here. */
private val FACTS_MAX = 560.dp

/** Between the overview and the file's facts beside it. */
private val COLUMN_GAP = 56.dp

/** Between the overview and the file's facts under it, on a narrow window. */
private val STACK_GAP = 28.dp

/** The overview's lines before "More": room for a whole paragraph under the hero. */
private const val SYNOPSIS_LINES = 6

/** The hero's words (title, facts, actions) keep to this width over the picture. */
private val HERO_TEXT = 760.dp

/** The hero's share of the window's height, kept between [HERO_MIN] and [HERO_MAX]. */
private const val HERO_SHARE = 0.62f
private val HERO_MIN = 320.dp
private val HERO_MAX = 680.dp

/** The hero's words stay below this, clear of the Back button over the picture. */
private val HERO_TOP = 88.dp

/** From this window height the title logo is drawn at its full size. */
private val TALL_WINDOW = 760.dp

/**
 * The overview's column and the file's facts: side by side where there is room for both (the
 * facts from a fixed line, so they never stand right after a short caption on one page and far
 * from it on the next), else one under the other. An empty [story] (no overview, copies or
 * bookmarks) leaves the facts at the start rather than after an empty column.
 */
@Composable
private fun StoryBesideFacts(story: @Composable () -> Unit, facts: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = { Box { story() }; Box { facts() } }) { measurables, constraints ->
        val total = constraints.maxWidth
        val gap = COLUMN_GAP.roundToPx()
        val factsMin = FACTS_MIN.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (total >= READING_MIN.roundToPx() + gap + factsMin) {
            val storyWidth = minOf(READING.roundToPx(), total - gap - factsMin)
            val first = measurables[0].measure(loose.copy(maxWidth = storyWidth))
            val factsAt = if (first.width == 0) 0 else storyWidth + gap
            val second = measurables[1].measure(loose.copy(maxWidth = minOf(FACTS_MAX.roundToPx(), total - factsAt)))
            layout(total, maxOf(first.height, second.height)) {
                first.place(0, 0)
                second.place(factsAt, 0)
            }
        } else {
            val first = measurables[0].measure(loose.copy(maxWidth = minOf(READING.roundToPx(), total)))
            val second = measurables[1].measure(loose.copy(maxWidth = minOf(FACTS_MAX.roundToPx(), total)))
            val below = if (first.height == 0) 0 else first.height + STACK_GAP.roundToPx()
            layout(total, below + second.height) {
                first.place(0, 0)
                second.place(0, below)
            }
        }
    }
}

/** The round actions beside Play: as tall as the large pill. */
internal val WIDE_ACTION = 56.dp

private val WIDE_JOINERS = Regex("[-_.]+")
