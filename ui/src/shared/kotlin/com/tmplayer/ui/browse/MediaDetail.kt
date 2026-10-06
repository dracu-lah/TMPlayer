package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MetaFactsLines
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/*
 * The detail panel (R5), shared by the phone, the television and the desktop: what a video is,
 * what can be done with it, and room for a poster and an overview once online metadata arrives.
 * Each platform puts its own chrome round it: a bottom sheet on the phone, a side pane in a window
 * of its own on the television, a side pane over the page on the desktop. Which actions it offers
 * is decided by [com.tmplayer.data.DetailActions], not here.
 *
 * With posters and overviews on ([OnlineMetadata]), a match adds the provider's picture over the
 * video's own, a poster beside the title, the episode's name, and the overview in place of the
 * caption, credited underneath. With them off, one more line offers to turn them on.
 */

/** One action line, worded and wired by the platform that opened the panel. */
data class DetailRow(
    val action: DetailAction,
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val destructive: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * The usual wording and icon for [action]. A platform overrides a line where it knows more, such
 * as which stage a queued download is at, by building that [DetailRow] itself.
 */
fun detailRow(
    action: DetailAction,
    s: Messages,
    watched: WatchPoint?,
    chatTitle: String,
    onSelect: () -> Unit,
    /** The "x GB free" after a download line's detail, where the platform can measure it. */
    free: String? = null,
): DetailRow {
    fun withFree(detail: String) = if (free == null) detail else "$detail  ·  $free"
    return when (action) {
        DetailAction.Resume -> DetailRow(
            action,
            s.detailResumeAt(s.formatter.clock(watched?.positionMs ?: 0L)),
            Icons.Filled.PlayArrow,
            s.detailResumeAtDetail,
            onSelect = onSelect,
        )
        DetailAction.Play -> DetailRow(action, s.commonPlay, Icons.Filled.PlayArrow, onSelect = onSelect)
        DetailAction.StartOver -> DetailRow(action, s.detailStartOver, Icons.Filled.Refresh, s.detailStartOverDetail, onSelect = onSelect)
        DetailAction.MarkWatched -> DetailRow(action, s.gridMarkWatched, Icons.Filled.Check, s.gridMarkWatchedDetail, onSelect = onSelect)
        DetailAction.MarkUnwatched -> DetailRow(action, s.gridMarkUnwatched, Icons.Filled.Close, s.gridMarkUnwatchedDetail, onSelect = onSelect)
        DetailAction.Download -> DetailRow(action, s.gridDownload, TmIcons.Download, withFree(s.gridDownloadDetail), onSelect = onSelect)
        DetailAction.SaveToDownloads ->
            DetailRow(action, s.gridSaveToDownloads, TmIcons.Download, withFree(s.gridSaveToDownloadsDetail), onSelect = onSelect)
        DetailAction.InDownloads -> DetailRow(action, s.gridInDownloads, TmIcons.Download, s.gridInDownloadsDetail, onSelect = onSelect)
        DetailAction.RemoveDownload -> DetailRow(action, s.gridRemoveDownload, Icons.Filled.Close, destructive = true, onSelect = onSelect)
        DetailAction.CancelDownload -> DetailRow(action, s.gridCancelRunning, Icons.Filled.Close, onSelect = onSelect)
        DetailAction.SelectVideos -> DetailRow(action, s.gridSelectVideos, TmIcons.Checklist, s.gridSelectVideosDetail, onSelect = onSelect)
        DetailAction.Share -> DetailRow(action, s.commonShare, TmIcons.Share, onSelect = onSelect)
        DetailAction.OpenElsewhere -> DetailRow(action, s.gridOpenInOtherPlayer, Icons.AutoMirrored.Filled.ExitToApp, onSelect = onSelect)
        DetailAction.CopyLink -> DetailRow(action, s.gridCopyLink, TmIcons.Share, s.gridCopyLinkDetail, onSelect = onSelect)
        DetailAction.OpenChat -> DetailRow(
            action,
            s.detailOpenChat(chatTitle),
            Icons.AutoMirrored.Filled.ArrowForward,
            s.detailOpenChatDetail,
            onSelect = onSelect,
        )
    }
}

/**
 * The panel's body. Scrolls as a whole, and on a remote every block in it can be stood on, so the
 * D-pad reaches the facts and the synopsis below the actions instead of stopping at the last button.
 *
 * @param firstAction focused when the panel opens on a remote or a keyboard; null leaves focus be.
 * @param poster a picture to show instead of the video's own. Left null, the online match's is used.
 * @param overview words to show instead of the caption. Left null, the online match's are used.
 */
@Composable
fun MediaDetailPanel(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    firstAction: FocusRequester? = null,
    /** The television's narrow pane: a smaller poster beside the title. */
    compactArt: Boolean = false,
    poster: (@Composable () -> Unit)? = null,
    overview: String? = null,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    /**
     * Opens another video's detail, for "More like this". Null leaves that row out. The row only
     * lists videos the viewer has in their chats.
     */
    onOpenItem: ((MediaItem) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val parsed = remember(item) { MediaName.parse(item.fileName.ifBlank { item.title }, item.caption) }
    val facts = remember(item) { MediaFacts.of(item) }
    val meta = rememberMeta(item)
    // "small-shelf-part-1" reads as a title once its joiners are spaces; a name with spaces in it
    // already chose its own punctuation and keeps it. A match's own title wins, in the UI language.
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(JOINERS, " ") }
    val episodeLine = listOfNotNull(
        parsed.episodeCode,
        meta?.episode?.name?.takeIf { it.isNotBlank() },
        (parsed.year ?: meta?.year)?.toString(),
    ).joinToString("  ·  ")
    val metaOverview = meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
    // The facts line, cast, trailer and "More like this": only for the panel's own online match,
    // never where a platform passed its own picture and words.
    val extras = rememberMetaExtras(meta.takeIf { poster == null && overview == null })
    val trailers = rememberTrailerLauncher()

    Column(
        modifier
            .verticalScroll(scroll)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 16:9 on every screen, the television's narrow pane included: a strip cut a
                // video's frame down to its middle band, and every other picture of it is 16:9.
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Corner.Medium))
                .background(Tone.surfaceHigh),
        ) {
            if (poster != null) {
                poster()
            } else {
                MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(40.dp))
                }
                // The episode's still or the wide picture, over the frame once it has arrived.
                MetaPicture(meta?.wideUrl, Modifier.fillMaxSize(), maxWidth = 800)
            }
            val fraction = when {
                finished -> 1f
                watched != null && watched.fraction > 0f -> watched.fraction
                else -> 0f
            }
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Tone.accent))
                }
            }
            if (finished) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            meta?.posterUrl?.let { url ->
                Box(
                    Modifier
                        .width(if (compactArt) DETAIL_POSTER_TV else DETAIL_POSTER)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Corner.Small))
                        .background(Tone.surfaceHigh),
                ) {
                    MetaPicture(url, Modifier.fillMaxSize(), maxWidth = 240, contentDescription = s.metadataPoster)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (episodeLine.isNotEmpty()) {
                    Text(episodeLine, style = MaterialTheme.typography.titleSmall, color = Tone.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (chatTitle.isNotBlank()) {
                    Text(
                        s.detailInChat(chatTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val quick = listOfNotNull(
                    facts.resolution,
                    item.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
                    item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
                    s.detailWatchedLine.takeIf { finished },
                )
                if (quick.isNotEmpty()) {
                    Text(
                        quick.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        MetaFactsLines(extras)

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

        Column(
            Modifier.semantics { },
            verticalArrangement = Arrangement.spacedBy(if (isTouch()) 0.dp else 8.dp),
        ) {
            rows.forEachIndexed { index, row ->
                DetailActionRow(
                    row,
                    if (index == 0 && firstAction != null) Modifier.focusRequester(firstAction) else Modifier,
                )
            }
            extras?.trailer?.let { trailer ->
                DetailActionRow(
                    DetailRow(
                        action = DetailAction.Play,
                        label = s.metadataTrailer,
                        icon = TrailerIcon,
                        onSelect = { trailers.open(trailer) },
                    ),
                )
                trailers.unopened?.takeIf { it == trailer }?.let { TrailerQr(it, Modifier.padding(vertical = 8.dp)) }
                TrailerHost(trailers)
            }
            // Posters and overviews are off: one line to turn them on from here, as Settings can.
            val online = OnlineMetadata.current
            if (online != null && poster == null && overview == null) {
                val settings by online.settings.collectAsState()
                if (!settings.enabled) {
                    DetailActionRow(
                        DetailRow(
                            action = DetailAction.Play,
                            label = s.metadataTurnOn,
                            icon = Icons.Filled.Info,
                            detail = s.metadataTurnOnDetail,
                            onSelect = { online.setEnabled(true) },
                        ),
                    )
                }
            }
        }

        DetailFacts(item, facts, s, overview ?: metaOverview, meta.takeIf { overview == null && metaOverview != null })

        if (meta != null && extras != null) {
            MetaCastRow(extras.cast)
            MoreLikeThisRow(meta, extras, onOpenItem)
            if (metaOverview == null && (extras.cast.isNotEmpty() || extras.genres.isNotEmpty())) MetaCredit(meta)
        }
    }
}

/**
 * The facts table and the synopsis. One block, focusable on a remote so the D-pad can scroll down
 * to it: without a stop of its own it would sit under the last button, out of reach.
 */
@Composable
internal fun DetailFacts(item: MediaItem, facts: MediaFacts, s: Messages, overview: String?, credit: MetaInfo? = null, showSynopsis: Boolean = true) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val remote = !isTouch()
    val lines = buildList {
        add(s.detailFileName to item.fileName.ifBlank { item.title })
        facts.resolution?.let { add(s.detailResolution to listOfNotNull(it, facts.pixels).joinToString("  ·  ")) }
        listOfNotNull(facts.videoCodec, facts.dynamicRange, facts.container).takeIf { it.isNotEmpty() }
            ?.let { add(s.detailVideo to it.joinToString("  ·  ")) }
        facts.audio?.let { add(s.detailAudio to it) }
        facts.tracks.takeIf { it.isNotEmpty() }?.let { tracks ->
            add(
                s.detailTracks to tracks.joinToString("  ·  ") {
                    when (it) {
                        MediaFacts.Track.DualAudio -> s.detailTrackDualAudio
                        MediaFacts.Track.MultiAudio -> s.detailTrackMultiAudio
                        MediaFacts.Track.Subtitles -> s.detailTrackSubtitles
                    }
                },
            )
        }
        facts.source?.let { add(s.detailSource to it) }
        if (item.sizeBytes > 0) add(s.detailSize to s.formatter.size(item.sizeBytes))
        if (item.durationSec > 0) add(s.detailLength to s.formatter.duration(item.durationSec.toLong()))
        if (item.date > TELEGRAM_LAUNCH) add(s.detailPosted to s.formatter.date(item.date * 1000L))
    }
    val synopsis = (overview?.takeIf { it.isNotBlank() } ?: item.caption.takeIf { it.isNotBlank() })
        ?.takeIf { showSynopsis }
    val fromName = facts.videoCodec != null || facts.audio != null || facts.tracks.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (remote) Modifier.focusRing(focused, shape).focusable(interactionSource = interactions) else Modifier)
            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = if (remote) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.width(FACT_LABEL),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (fromName) {
            Text(s.detailFromNameNote, style = MaterialTheme.typography.labelSmall, color = Tone.muted)
        }
        if (synopsis != null) {
            Spacer(Modifier.height(4.dp))
            Text(s.detailAbout, style = MaterialTheme.typography.titleSmall, color = Tone.text)
            Text(synopsis, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
            credit?.let { MetaCredit(it) }
        }
    }
}

/**
 * One action. On a remote or a keyboard the row is a filled tile that takes the focus colour, the
 * way the television's menus are; under a finger or a pointer it is a list line with a ripple.
 */
@Composable
private fun DetailActionRow(row: DetailRow, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val touch = isTouch()
    val shape = RoundedCornerShape(Corner.Medium)
    val background = when {
        focused && row.destructive -> Danger
        focused -> Tone.focusFill
        touch -> Color.Transparent
        else -> Tone.surfaceHigh
    }
    val foreground = when {
        focused -> Tone.onFocusFill
        row.destructive -> Tone.danger
        else -> Tone.text
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .focusRing(focused, shape)
            .clickable(interactionSource = interactions, indication = if (touch) androidx.compose.foundation.LocalIndication.current else null, onClick = row.onSelect)
            .padding(horizontal = if (touch) 12.dp else 16.dp, vertical = if (touch) 12.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = if (focused) foreground else if (row.destructive) Tone.danger else Tone.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.detail != null) {
                Text(
                    row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How wide the side pane is on a television and a desktop. */
val DETAIL_PANE_WIDTH = 440.dp

private val DETAIL_POSTER = 84.dp
private val DETAIL_POSTER_TV = 72.dp
private val FACT_LABEL = 112.dp
private val JOINERS = Regex("[-_.]+")

/** August 2013, in seconds: a post dated before Telegram existed is a fixture, not a date worth printing. */
private const val TELEGRAM_LAUNCH = 1_375_000_000
