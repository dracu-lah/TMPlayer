package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.background
import com.tmplayer.ui.theme.trackedFor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.tmplayer.data.Bookmark
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.ArtBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Scrims
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
 * The detail panel (R5) for a narrow pane: what a video is, what can be done with it, and room
 * for a poster and an overview once online metadata arrives. The television puts it in a side
 * pane in a window of its own; the desktop draws the large [WideDetailPanel] on a sheet over the
 * page instead, and the phone its own page ([MediaDetailScreen]). Which actions it offers is
 * decided by [com.tmplayer.data.DetailActions], not here.
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
    /** A download under way, 0 to 1, for the ring round its button; null for anything else. */
    val progress: Float? = null,
    /** A place held for a button still on its way (the trailer): laid out, unseen, unreachable. */
    val placeholder: Boolean = false,
    /**
     * False for a line that only says how things stand (a video already downloaded): drawn
     * dimmed and does nothing, but a remote can still stand on it, so focus is not thrown
     * elsewhere when the Download button it was on turns into this.
     */
    val enabled: Boolean = true,
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
            TmIcons.Play,
            s.detailResumeAtDetail,
            onSelect = onSelect,
        )
        DetailAction.Play -> DetailRow(action, s.commonPlay, TmIcons.Play, onSelect = onSelect)
        DetailAction.StartOver -> DetailRow(action, s.detailStartOver, TmIcons.Refresh, s.detailStartOverDetail, onSelect = onSelect)
        DetailAction.MarkWatched -> DetailRow(action, s.gridMarkWatched, TmIcons.Check, s.gridMarkWatchedDetail, onSelect = onSelect)
        DetailAction.MarkUnwatched -> DetailRow(action, s.gridMarkUnwatched, TmIcons.Close, s.gridMarkUnwatchedDetail, onSelect = onSelect)
        DetailAction.AddWatchLater -> DetailRow(action, s.detailWatchLaterAdd, TmIcons.Clock, s.detailWatchLaterAddDetail, onSelect = onSelect)
        DetailAction.RemoveWatchLater -> DetailRow(action, s.detailWatchLaterRemove, TmIcons.Close, onSelect = onSelect)
        DetailAction.RemoveFromHistory ->
            DetailRow(action, s.browseRemoveFromHistory, TmIcons.History, s.browseRemoveFromHistoryDetail, destructive = true, onSelect = onSelect)
        DetailAction.Download -> DetailRow(action, s.gridDownload, TmIcons.Download, withFree(s.gridDownloadDetail), onSelect = onSelect)
        DetailAction.SaveToDownloads ->
            DetailRow(action, s.gridSaveToDownloads, TmIcons.Download, withFree(s.gridSaveToDownloadsDetail), onSelect = onSelect)
        DetailAction.InDownloads -> DetailRow(action, s.gridInDownloads, TmIcons.Download, s.gridInDownloadsDetail, onSelect = onSelect)
        DetailAction.RemoveDownload -> DetailRow(action, s.gridRemoveDownload, TmIcons.Delete, destructive = true, onSelect = onSelect)
        DetailAction.CancelDownload -> DetailRow(action, s.gridCancelRunning, TmIcons.Close, onSelect = onSelect)
        DetailAction.SelectVideos -> DetailRow(action, s.gridSelectVideos, TmIcons.Checklist, s.gridSelectVideosDetail, onSelect = onSelect)
        DetailAction.Share -> DetailRow(action, s.commonShare, TmIcons.Share, onSelect = onSelect)
        DetailAction.OpenElsewhere -> DetailRow(action, s.gridOpenInOtherPlayer, TmIcons.OpenExternal, onSelect = onSelect)
        DetailAction.CopyLink -> DetailRow(action, s.gridCopyLink, TmIcons.Share, s.gridCopyLinkDetail, onSelect = onSelect)
        DetailAction.OpenChat -> DetailRow(
            action,
            s.detailOpenChat(chatTitle),
            TmIcons.ArrowForward,
            s.detailOpenChatDetail,
            onSelect = onSelect,
        )
        DetailAction.SplitCopy -> DetailRow(action, s.gridNotSameVideo, TmIcons.Remove, s.gridNotSameVideoDetail, onSelect = onSelect)
        DetailAction.JoinCopies -> DetailRow(action, s.gridSameVideoAgain, TmIcons.Add, onSelect = onSelect)
        DetailAction.WrongMatch -> DetailRow(action, s.gridWrongMatch, TmIcons.VisibilityOff, s.gridWrongMatchDetail, onSelect = onSelect)
        DetailAction.UseMatch -> DetailRow(action, s.gridMatchAgain, TmIcons.Image, onSelect = onSelect)
        DetailAction.Rename -> DetailRow(action, s.gridRename, TmIcons.Edit, s.gridRenameDetail, onSelect = onSelect)
        DetailAction.CommentVideos -> DetailRow(action, s.detailCommentsVideos, TmIcons.Chats, onSelect = onSelect)
        DetailAction.ExportCopy -> DetailRow(action, s.keepExportGallery, TmIcons.Image, s.keepExportGalleryDetail, onSelect = onSelect)
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
@OptIn(ExperimentalLayoutApi::class)
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
    /** This video's bookmarks, listed under the actions; see [DetailBookmarks]. */
    bookmarks: List<Bookmark> = emptyList(),
    onOpenBookmark: ((Bookmark) -> Unit)? = null,
    /** Another copy chosen from the film's copies list; see [MediaDetailScreen]. */
    onPickCopy: ((MediaItem) -> Unit)? = onOpenItem,
    copyStarted: (MediaItem) -> Boolean = { false },
    /**
     * Only the everyday buttons under Play (watched, watch later, download, the trailer), and the
     * rest (the chat, the corrections, share, open elsewhere) centred at the foot of the panel, as
     * a phone's video page has them. The TV keeps one row.
     */
    actionsAtFoot: Boolean = false,
) {
    val s = LocalStrings.current
    // The screen's one collector when it has one, rather than a second of its own.
    val corrections by com.tmplayer.ui.components.rememberCorrections()
    val parsed = remember(item, corrections) { com.tmplayer.data.SeriesShelf.episodeOf(item, corrections) }
    val matched = com.tmplayer.ui.online.rememberMatched(item)
    val meta = matched?.info
    // "small-shelf-part-1" reads as a title once its joiners are spaces; a name with spaces in it
    // already chose its own punctuation and keeps it. A match's own title wins, in the UI language.
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(JOINERS, " ") }
    val episodeLine = listOfNotNull(
        parsed.episodeCode,
        meta?.episode?.name?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
    val metaOverview = meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
    // The facts line, cast, trailer and "More like this": only for the panel's own online match,
    // never where a platform passed its own picture and words.
    val extras = rememberMetaExtras(meta.takeIf { poster == null && overview == null })
    val trailers = rememberTrailerLauncher()
    // The trailer's player, once for the page and outside its list: placed in the list's first
    // item it was composed twice while the first paint's list handed over to the live one, and
    // two players opened one over the other (sound over a black screen, 2026-10-22).
    TrailerHost(trailers)

    val cover = rememberCover(item)
    // A record's item (opened from Continue watching, say) carries no thumbnail: its picture is
    // looked up, as the card it was opened from did.
    val art = rememberArtItem(item)
    val source = remember(item.id, art, cover, meta?.wideUrl) { BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, meta?.wideUrl) }
    val facts = remember(item) { MediaFacts.of(item) }
    val online = OnlineMetadata.current
    // Collected once here: read behind the conditions below, a collector started and stopped as
    // the poster or the overview came and went.
    val metaEnabled = online?.let { com.tmplayer.ui.components.rememberMetaSettings(it) }?.value?.enabled
    val metadataOff = online != null && poster == null && overview == null && metaEnabled != true

    Column(
        modifier
            .verticalScroll(scroll)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
                // The online picture, else the thumbnail as a soft field, else the generated cover.
                ArtBackdrop(source, Modifier.fillMaxSize())
            }
            val fraction = when {
                finished -> 1f
                watched != null && watched.fraction > 0f -> watched.fraction
                else -> 0f
            }
            if (fraction > 0f) {
                Box(Modifier.matchParentSize().background(Scrims.tileFoot))
                com.tmplayer.ui.components.ProgressLine(fraction, Modifier.align(Alignment.BottomCenter))
            }
            if (finished) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
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
                if (episodeLine.isNotEmpty()) {
                    Text(episodeLine, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Tone.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    heading,
                    // The page's display size, a step down beside a poster in a pane this narrow.
                    style = if (meta?.posterUrl != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall.trackedFor(heading),
                    fontWeight = FontWeight.Bold,
                    color = Tone.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                // Where the name came from: the provider's match and what backed it, or the file.
                if (metaEnabled == true) {
                    Text(
                        meta?.let { m -> m.year?.let { s.detailMatchedAs(m.title, it.toString()) } ?: s.detailMatchedAsNoYear(m.title) }
                            ?: s.detailFromFileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Tone.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FactLine(
                listOfNotNull(
                    (parsed.year ?: meta?.year)?.let { Fact(it.toString()) },
                    item.durationSec.takeIf { it > 0 }?.let { Fact(s.formatter.duration(it.toLong())) },
                    // As the copy under Play names it ([VideoCopies.qualityLabel]).
                    (com.tmplayer.data.VideoCopies.qualityLabel(item) ?: facts.resolution)?.let { Fact(it, chip = true) },
                    item.sizeBytes.takeIf { it > 0 }?.let { Fact(com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(it))) },
                    chatTitle.takeIf { it.isNotBlank() }?.let { Fact(it) },
                    Fact(s.detailWatchedLine, accent = true).takeIf { finished },
                ),
            )
            // The file's own length is on the facts line already.
            MetaFactsLines(extras, runtime = item.durationSec <= 0)
            ResumeLine(watched, finished, item.durationSec)
            PiecesMissingLine(item)
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

        // One bright pill for Play or Resume, then every other action as a round button.
        rows.firstOrNull()?.let { primary ->
            HeroPill(primary.label, primary.icon, primary.onSelect, Modifier.fillMaxWidth(), focusRequester = firstAction)
        }
        val others = buildList {
            addAll(rows.drop(1).filter { !actionsAtFoot || it.action in HEADLINE_ACTIONS })
            extras?.trailer?.let { trailer -> add(DetailRow(DetailAction.Play, s.metadataTrailer, TrailerIcon, onSelect = { trailers.open(trailer) })) }
            // Posters and overviews are off: one button to turn them on from here, as Settings can.
            if (metadataOff && !actionsAtFoot) {
                add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online?.setEnabled(true) }))
            }
        }
        if (others.isNotEmpty()) {
            // Centred under Play on a phone, as on a video's page and a show's.
            val centred = isTouch()
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, if (centred) Alignment.CenterHorizontally else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                others.forEach { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), progress = it.progress, enabled = it.enabled) }
            }
        }
        extras?.trailer?.let { trailer -> trailers.unopened?.takeIf { it == trailer }?.let { TrailerQr(it, Modifier.padding(vertical = 8.dp)) } }

        // A film the chat posted several times over: every copy by quality and size, one click away.
        Versions(item, onPickCopy, chatTitle, copyStarted)
        // Telegram's own lighter copies of the upload (CP80), when it made any.
        TelegramQualityLine(item)

        DetailBookmarks(bookmarks, onOpenBookmark)

        DetailFacts(item, facts, s, overview ?: metaOverview, meta.takeIf { overview == null && metaOverview != null })

        if (meta != null && extras != null) {
            MetaCastRow(extras.cast)
            MoreLikeThisRow(meta, extras, onOpenItem)
            if (metaOverview == null && (extras.cast.isNotEmpty() || extras.genres.isNotEmpty())) MetaCredit(meta)
        }

        if (actionsAtFoot) {
            // Things done now and then, out of the way of Play.
            val rest = buildList {
                addAll(rows.drop(1).filter { it.action !in HEADLINE_ACTIONS })
                if (metadataOff) {
                    add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online?.setEnabled(true) }))
                }
            }
            if (rest.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rest.forEach { RoundAction(it.label, it.icon, it.onSelect, destructive = it.destructive, detail = it.detail, short = roundLabel(it, s), progress = it.progress, enabled = it.enabled) }
                }
            }
        }
    }
}

/**
 * The video's bookmarks, under a heading, each a line that opens the player at its moment: "Play
 * from 12:34" with the note under it. Nothing at all while there are none, or where the platform
 * has no way to open the player at a time ([onOpen] null).
 */
@Composable
internal fun DetailBookmarks(bookmarks: List<Bookmark>, onOpen: ((Bookmark) -> Unit)?) {
    if (bookmarks.isEmpty() || onOpen == null) return
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(if (isTouch()) 0.dp else 8.dp)) {
        Text(
            s.playerBookmarks,
            style = MaterialTheme.typography.labelLarge,
            color = Tone.muted,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        bookmarks.forEach { mark ->
            DetailActionRow(
                DetailRow(
                    action = DetailAction.Play,
                    label = s.detailBookmarkAt(s.formatter.clock(mark.positionMs)),
                    icon = TmIcons.Bookmark,
                    detail = mark.note.ifBlank { null },
                    onSelect = { onOpen(mark) },
                ),
            )
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
    val focused by interactions.collectIsFocusVisibleAsState()
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
        if (item.sizeBytes > 0) add(s.detailSize to com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(item.sizeBytes)))
        if (item.durationSec > 0) add(s.detailLength to s.formatter.duration(item.durationSec.toLong()))
        if (item.date > TELEGRAM_LAUNCH) add(s.detailPosted to s.formatter.date(item.date * 1000L))
    }
    val synopsis = (overview?.takeIf { it.isNotBlank() } ?: com.tmplayer.data.MediaText.overviewOf(item))
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
    val focused by interactions.collectIsFocusVisibleAsState()
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

/** How wide the side pane is on a television. */
val DETAIL_PANE_WIDTH = 440.dp

private val DETAIL_POSTER = 84.dp
private val DETAIL_POSTER_TV = 72.dp
private val FACT_LABEL = 112.dp
private val JOINERS = Regex("[-_.]+")

/** August 2013, in seconds: a post dated before Telegram existed is a fixture, not a date worth printing. */
private const val TELEGRAM_LAUNCH = 1_375_000_000
