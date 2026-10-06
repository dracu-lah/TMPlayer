package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.tmplayer.ui.components.TmIcons
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.online.MetaKind
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.MediaName
import com.tmplayer.data.WatchPoint
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.revealFromTop
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.online.MetaExtras
import com.tmplayer.ui.online.MetaFactsLines
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.data.DetailAction

/**
 * A video's detail as a whole screen, in the manner of a streaming app's title page, opened by a
 * long press, a hold of OK or the info key on a tile.
 *
 * A wide screen (a television, a phone on its side) keeps the picture across the top end of the
 * screen, faded into the background on its start and bottom edges, and writes the title, the facts
 * and the actions over the faded part. The picture stays where it is while the page scrolls up over
 * it to the cast, "More like this" and the file's facts, and dims as it goes, so it is never cut off
 * halfway as a picture scrolled out of a narrow pane was. A tall screen pins the picture across the
 * top and scrolls everything else under it.
 *
 * Close sits at the top end on both; Back closes as well.
 *
 * @param firstAction focused when the screen opens on a remote or a keyboard.
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
) {
    val meta = rememberMeta(item)
    val extras = rememberMetaExtras(meta)
    BoxWithConstraints(modifier.fillMaxSize().background(Tone.background)) {
        val wide = maxWidth > maxHeight
        if (wide) {
            CinemaLayout(item, chatTitle, watched, finished, rows, meta, extras, maxHeight, firstAction, onOpenItem)
        } else {
            StackedLayout(item, chatTitle, watched, finished, rows, meta, extras, firstAction, onOpenItem)
        }
        CloseButton(
            onClose,
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(if (wide && !isTouch()) 24.dp else 12.dp),
        )
    }
}

@Composable
private fun CinemaLayout(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    meta: MetaInfo?,
    extras: MetaExtras?,
    height: Dp,
    firstAction: FocusRequester?,
    onOpenItem: ((MediaItem) -> Unit)?,
) {
    val tv = !isTouch()
    val scroll = rememberScrollState()
    val episodes = remember { BringIntoViewRequester() }
    val side = if (tv) 48.dp else 24.dp
    // How far the page has to scroll before the picture is as dim as it gets.
    val dimOver = with(LocalDensity.current) { (height * 0.6f).toPx() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(
            item,
            meta,
            fadeStart = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.8f)
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
                // The words keep to the faded half; the buttons may run on under the picture,
                // so they stay on one line rather than wrapping in a narrow column.
                Box(Modifier.fillMaxWidth(0.55f)) {
                    TitleBlock(item, chatTitle, watched, finished, meta, extras, large = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionPills(rows, meta, extras, firstAction, onEpisodes = episodesButton(item, meta, episodes))
                    Versions(item, onOpenItem)
                }
                Spacer(Modifier.height(if (tv) 8.dp else 4.dp))
            }
            BelowTheFold(item, meta, extras, onOpenItem, Modifier.bringIntoViewRequester(episodes))
            Spacer(Modifier.height(24.dp))
        }
    }
    // Focus on the first action shows the first screen whole (see revealFromTop); the return to
    // the top once it has settled stays as a guard for a picture or a title that arrives late
    // and changes the height under it.
    LaunchedEffect(item.id) {
        runCatching { firstAction?.requestFocus() }
        kotlinx.coroutines.delay(FOCUS_SETTLE_MS)
        scroll.scrollTo(0)
    }
}

@Composable
private fun StackedLayout(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    meta: MetaInfo?,
    extras: MetaExtras?,
    firstAction: FocusRequester?,
    onOpenItem: ((MediaItem) -> Unit)?,
) {
    val scroll = rememberScrollState()
    val episodes = remember { BringIntoViewRequester() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Pinned: the rest scrolls under it, so the picture is never half off the top.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Backdrop(item, meta, fadeStart = false, modifier = Modifier.fillMaxSize())
            ProgressStrip(watched, finished, Modifier.align(Alignment.BottomStart))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Back up on the buttons from below with a remote or keys, the title above them shows too.
            Column(Modifier.revealFromTop(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TitleBlock(item, chatTitle, watched, finished, meta, extras, large = false, progress = false)
                ActionPills(rows, meta, extras, firstAction, primaryFullWidth = true, onEpisodes = episodesButton(item, meta, episodes))
                Versions(item, onOpenItem)
            }
            BelowTheFold(item, meta, extras, onOpenItem, Modifier.bringIntoViewRequester(episodes))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The film's wide picture, or the video's own frame until it comes, faded into the background. */
@Composable
private fun Backdrop(item: MediaItem, meta: MetaInfo?, fadeStart: Boolean, modifier: Modifier) {
    val ground = Tone.background
    Box(modifier) {
        MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(48.dp))
        }
        MetaPicture(meta?.wideUrl, Modifier.fillMaxSize(), maxWidth = 1280)
        if (fadeStart) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to ground, 0.35f to ground.copy(alpha = 0.6f), 0.7f to Color.Transparent),
                ),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to (if (fadeStart) Color.Transparent else ground.copy(alpha = 0.35f)),
                    0.2f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to ground,
                ),
            ),
        )
    }
}

/** The title, the episode or year, the facts, the synopsis and where the video is. */
@Composable
private fun TitleBlock(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    meta: MetaInfo?,
    extras: MetaExtras?,
    large: Boolean,
    progress: Boolean = true,
) {
    val s = LocalStrings.current
    val parsed = remember(item) { MediaName.parse(item.fileName.ifBlank { item.title }, item.caption) }
    val facts = remember(item) { MediaFacts.of(item) }
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(JOINERS, " ") }
    val episodeLine = listOfNotNull(
        parsed.episodeCode,
        meta?.episode?.name?.takeIf { it.isNotBlank() },
        (parsed.year ?: meta?.year)?.toString(),
    ).joinToString("  ·  ")
    val synopsis = meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
        ?: item.caption.takeIf { it.isNotBlank() }
    val quick = listOfNotNull(
        facts.resolution,
        item.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
        item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
        s.detailWatchedLine.takeIf { finished },
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (episodeLine.isNotEmpty()) {
            Text(episodeLine, style = MaterialTheme.typography.titleMedium, color = Tone.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            heading,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Tone.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (quick.isNotEmpty()) {
            Text(quick.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MetaFactsLines(extras)
        if (progress) ProgressStrip(watched, finished, Modifier.widthIn(max = 320.dp).clip(CircleShape))
        if (synopsis != null) {
            Text(
                synopsis,
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = Tone.text,
                maxLines = if (large) 2 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.canBeSaved) {
            Text(
                s.detailProtected,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.Small))
                    .background(Tone.surfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** How far through the video the viewer is, as a thin bar; nothing for one never started. */
@Composable
private fun ProgressStrip(watched: WatchPoint?, finished: Boolean, modifier: Modifier = Modifier) {
    val fraction = when {
        finished -> 1f
        watched != null && watched.fraction > 0f -> watched.fraction
        else -> 0f
    }
    if (fraction <= 0f) return
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Tone.accent))
    }
}

/**
 * The actions as buttons in a row that wraps: the first (Resume or Play) filled, the rest quiet.
 * The trailer and the switch for posters and overviews join them as the old panel's lines did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPills(
    rows: List<DetailRow>,
    meta: MetaInfo?,
    extras: MetaExtras?,
    firstAction: FocusRequester?,
    primaryFullWidth: Boolean = false,
    /** Scrolls down to the seasons and episodes; null where the video is not an episode. */
    onEpisodes: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val trailers = rememberTrailerLauncher()
    // Posters and overviews are off: one button to turn them on from here, as Settings can.
    val online = OnlineMetadata.current
    val metadataOff = online != null && !online.settings.collectAsState().value.enabled
    val trailer = extras?.trailer
    val all = buildList {
        addAll(rows)
        // Beside the main button, as a streaming app's "Episodes" sits by Play.
        if (onEpisodes != null) add(minOf(1, size), DetailRow(DetailAction.Play, s.detailEpisodes, TmIcons.Episodes, onSelect = onEpisodes))
        if (trailer != null) {
            add(DetailRow(DetailAction.Play, s.metadataTrailer, TrailerIcon, onSelect = { trailers.open(trailer) }))
        }
        if (online != null && meta == null && metadataOff) {
            add(DetailRow(DetailAction.Play, s.metadataTurnOn, Icons.Filled.Info, onSelect = { online.setEnabled(true) }))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            all.forEachIndexed { index, row ->
                val first = index == 0
                Pill(
                    row,
                    primary = first,
                    modifier = Modifier
                        .then(if (first && primaryFullWidth) Modifier.fillMaxWidth() else Modifier)
                        .then(if (first && firstAction != null) Modifier.focusRequester(firstAction) else Modifier),
                )
            }
        }
        trailers.unopened?.let { TrailerQr(it, Modifier.padding(vertical = 8.dp)) }
        TrailerHost(trailers)
    }
}

/**
 * The other copies of this film in the chat, by quality and size, the one on the page marked:
 * "720p · 900 MB", "1080p · HEVC · 1.8 GB". Choosing one turns the page to it, so Play, the
 * download line and the facts all speak of that copy. Nothing when the film was posted once.
 */
@Composable
private fun Versions(item: MediaItem, onOpenItem: ((MediaItem) -> Unit)?) {
    if (item.versions.size <= 1 || onOpenItem == null) return
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            s.detailVersions(item.versions.size),
            style = MaterialTheme.typography.labelLarge,
            color = Tone.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.versions.forEach { copy ->
                CopyChip(copy, chosen = copy.id == item.id, onClick = { if (copy.id != item.id) onOpenItem(copy.asVersion(item)) })
            }
        }
    }
}

/** One action: filled for the main one, a quiet outline for the rest, the focus colour under a remote. */
@Composable
private fun Pill(row: DetailRow, primary: Boolean, modifier: Modifier = Modifier) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val background = when {
        focused && row.destructive -> Danger
        focused -> Tone.focusFill
        primary -> Color.White
        else -> Tone.surfaceHigh
    }
    val ink = when {
        focused -> Tone.onFocusFill
        primary -> Color.Black
        row.destructive -> Tone.danger
        else -> Tone.text
    }
    Row(
        modifier
            .height(if (tv) 52.dp else 44.dp)
            .clip(shape)
            .background(background)
            .focusRing(focused, shape)
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = row.onSelect,
            )
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(row.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(
            row.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The cast, "More like this", then the file's facts, below the first screen. */
@Composable
private fun BelowTheFold(
    item: MediaItem,
    meta: MetaInfo?,
    extras: MetaExtras?,
    onOpenItem: ((MediaItem) -> Unit)?,
    episodesModifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val facts = remember(item) { MediaFacts.of(item) }
    // The cast first, a short row; then the seasons and episodes, which can run long, so they
    // never push the cast out of sight; then "More like this".
    if (meta != null && extras != null) MetaCastRow(extras.cast)
    SeasonsSection(item, meta, extras, onOpenItem, episodesModifier)
    if (meta != null && extras != null) MoreLikeThisRow(meta, extras, onOpenItem)
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.detailAbout, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        DetailFacts(item, facts, s, overview = null, showSynopsis = false)
        meta?.let { MetaCredit(it) }
    }
}

/** Close, at the top end, on a dark disc so it reads over any picture. */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(
        modifier
            .size(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClose)
            .semantics { contentDescription = s.commonClose },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = if (focused) Tone.onFocusFill else Color.White, modifier = Modifier.size(24.dp))
    }
}

/** The Episodes button's action for an episode of a show, or null for anything else. */
@Composable
private fun episodesButton(item: MediaItem, meta: MetaInfo?, target: BringIntoViewRequester): (() -> Unit)? {
    val episode = remember(item.id) { SeriesShelf.episodeOf(item).episode != null }
    if (!episode && meta?.kind != MetaKind.Show) return null
    val scope = rememberCoroutineScope()
    return { scope.launch { target.bringIntoView() } }
}

private const val FOCUS_SETTLE_MS = 120L
private val JOINERS = Regex("[-_.]+")
