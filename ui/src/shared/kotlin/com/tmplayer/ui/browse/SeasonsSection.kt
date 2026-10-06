package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.TmIcons
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeGuide
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.online.KnownMedia
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * How far the viewer is through each video, for the episode list on a detail page. The screen that
 * opens the page provides it; without one the list shows no progress.
 */
val LocalSeriesWatch = compositionLocalOf { SeriesWatch.None }

/**
 * A show's episodes on its detail page: how many seasons it has and what airs next, the seasons as
 * tabs, and the chosen season's episodes. What the chat holds plays; what the provider lists but
 * nobody posted here is drawn in grey and does nothing, and what has not aired is marked upcoming.
 *
 * The chat's episodes come from [KnownMedia], which every loaded page of the chat feeds, so an
 * episode posted while the page is open turns playable once the chat's listing is refreshed.
 * Nothing at all for a film or a lone video with no online match as a show.
 */
@Composable
fun SeasonsSection(
    item: MediaItem,
    meta: MetaInfo?,
    extras: MetaExtras?,
    onOpenItem: ((MediaItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val watch = LocalSeriesWatch.current
    val parsed = remember(item.id) { SeriesShelf.episodeOf(item) }
    val show = meta?.kind == MetaKind.Show
    if (parsed.episode == null && !show) return

    val known by KnownMedia.version.collectAsState()
    val series by produceState<Series?>(null, item.id, known) {
        value = withContext(Dispatchers.Default) { EpisodeGuide.seriesOf(item, KnownMedia.all()) }
    }
    val today = remember { LocalDate.now().toString() }
    val seasons = remember(series, extras, today) {
        EpisodeGuide.seasons(series, extras?.seasonList.orEmpty(), extras?.nextEpisode, today)
    }
    // A lone episode with nothing else to say: the page already shows it.
    if (seasons.isEmpty() || (series == null && extras?.seasonList.isNullOrEmpty())) return

    val start = parsed.season ?: meta?.episode?.season ?: seasons.firstOrNull { it.state == EpisodeGuide.State.InChat }?.number ?: seasons.first().number
    var selected by remember(item.id) { mutableIntStateOf(start) }
    val online = OnlineMetadata.current
    val listed by produceState(meta?.let { online?.peekSeason(it, selected) }, meta, selected) {
        value = if (meta == null || online == null) null else withContext(Dispatchers.IO) { online.season(meta, selected) }
    }
    val episodes = remember(selected, series, listed, today) { EpisodeGuide.episodes(selected, series, listed, today) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(TmIcons.Episodes, contentDescription = null, tint = Tone.text, modifier = Modifier.size(22.dp))
            Text(s.detailEpisodes, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        }
        statusLine(extras, s)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted) }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { season ->
                SeasonChip(season, chosen = season.number == selected, tv = tv) { selected = season.number }
            }
        }
        // Said plainly, in the caution colour: a gap in a season reads as the app losing episodes
        // unless something says they were never posted here. Upcoming ones are not missing.
        val missing = episodes.count { it.state == EpisodeGuide.State.Missing }
        if (missing > 0) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(Corner.Medium))
                    .background(Tone.caution.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = Tone.caution, modifier = Modifier.size(20.dp))
                Text(s.detailMissingCount(missing), style = MaterialTheme.typography.bodyMedium, color = Tone.text)
            }
        } else if (seasons.any { it.state != EpisodeGuide.State.InChat }) {
            Text(s.detailNotInChatHint, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        }

        Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 4.dp)) {
            episodes.forEach { episode ->
                GuideRow(
                    episode = episode,
                    current = episode.inChat?.copies?.any { it.id == item.id } == true,
                    tv = tv,
                    onOpen = episode.inChat?.takeIf { onOpenItem != null }?.let { inChat ->
                        { onOpenItem?.invoke(inChat.copies.firstOrNull { it.id == item.id } ?: inChat.item) }
                    },
                    watch = watch,
                )
            }
        }
    }
}

/** "5 seasons", then the next episode or season and its day, or that the show has ended. */
private fun statusLine(extras: MetaExtras?, s: com.tmplayer.i18n.Messages): String? {
    if (extras == null) return null
    val parts = buildList {
        extras.seasons?.let { add(s.detailSeasonsCount(it)) }
        val next = extras.nextEpisode
        when {
            next != null && next.episode == 1 -> add(s.detailSeasonStarts(next.season, day(next.airDate, s)))
            next != null -> add(s.detailNextEpisode("S%02dE%02d".format(java.util.Locale.ROOT, next.season, next.episode), day(next.airDate, s)))
            extras.ended -> add(s.detailShowEnded)
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/** A provider's `2026-10-14` in the UI language's own way of writing a date. */
private fun day(iso: String, s: com.tmplayer.i18n.Messages): String =
    runCatching { s.formatter.date(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }.getOrDefault(iso)

/**
 * One season's tab: its number and how many of its episodes are here. A season the chat has
 * nothing of is drawn faint and cannot be chosen, with why under its number.
 */
@Composable
private fun SeasonChip(season: EpisodeGuide.Season, chosen: Boolean, tv: Boolean, onSelect: () -> Unit) {
    val s = LocalStrings.current
    val usable = season.state == EpisodeGuide.State.InChat
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val fill = when {
        focused -> Tone.focusFill
        chosen -> Tone.accent.copy(alpha = 0.18f)
        else -> Tone.surfaceHigh
    }
    Column(
        Modifier
            .alpha(if (usable) 1f else 0.5f)
            .clip(shape)
            .background(fill)
            .border(if (chosen && !focused) 1.5.dp else 0.dp, if (chosen && !focused) Tone.accent else Color.Transparent, shape)
            .focusRing(focused, shape)
            .semantics {
                role = Role.Tab
                this.selected = chosen
            }
            .then(if (usable) Modifier.clickable(interactionSource = interactions, indication = null, onClick = onSelect) else Modifier)
            .padding(horizontal = if (tv) 20.dp else 16.dp, vertical = if (tv) 10.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            s.seriesSeason(season.number),
            style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                focused -> Tone.onFocusFill
                chosen -> Tone.accent
                else -> Tone.text
            },
            maxLines = 1,
        )
        Text(
            when (season.state) {
                EpisodeGuide.State.InChat -> season.total?.let { s.detailInChatCount(season.inChat, it) } ?: s.seriesEpisodesCount(season.inChat)
                EpisodeGuide.State.Missing -> s.detailNotInChat
                EpisodeGuide.State.Upcoming -> season.airDate?.let { s.detailAirsOn(day(it, s)) } ?: s.detailUpcoming
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
            maxLines = 1,
        )
    }
}

/**
 * One episode: its picture, code and name, length, and where it stands. In the chat it opens that
 * episode's page; missing or upcoming it is faint, takes no focus and does nothing.
 */
@Composable
private fun GuideRow(
    episode: EpisodeGuide.Episode,
    current: Boolean,
    tv: Boolean,
    onOpen: (() -> Unit)?,
    watch: SeriesWatch,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val inChat = episode.inChat
    val point = inChat?.let(watch::pointOf)
    val finished = inChat != null && watch.finishedEpisode(inChat)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (inChat != null) 1f else 0.45f)
            .clip(shape)
            .background(
                when {
                    focused -> Tone.focusFill
                    current -> Tone.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .focusRing(focused, shape)
            .then(
                if (onOpen != null) {
                    Modifier.clickable(
                        interactionSource = interactions,
                        indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                        onClick = onOpen,
                    )
                } else {
                    Modifier
                },
            )
            .padding(if (tv) 8.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (tv) 200.dp else 128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Corner.Small))
                .background(Tone.surfaceHigh),
        ) {
            // The episode's own still from the show's season listing, else the episode file's own
            // match, which is the same show and episode. Never the show's backdrop: a column of one
            // picture tells the episodes apart less than Telegram's frames, which stay underneath.
            if (inChat != null) MediaArt(inChat.item.miniThumbnail, inChat.item.thumbnailFileId, Modifier.fillMaxSize()) {}
            val own = inChat?.takeIf { episode.meta?.stillUrl == null }?.let { rememberMeta(it.item) }
            MetaPicture(episode.meta?.stillUrl ?: own?.episode?.stillUrl, Modifier.fillMaxSize(), maxWidth = 400)
            val fraction = if (finished) 1f else point?.fraction ?: 0f
            if (fraction > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Tone.accent))
                }
            }
            if (finished) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ink = if (focused) Tone.onFocusFill else Tone.text
            Text(
                listOfNotNull(episode.code, episode.meta?.name?.takeIf { it.isNotBlank() }).joinToString("  "),
                style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val length = (episode.meta?.runtimeMin?.let { it * 60L } ?: inChat?.item?.durationSec?.takeIf { it > 0 }?.toLong())
                ?.let { s.formatter.duration(it) }
            val airs = episode.meta?.airDate?.takeIf { episode.state == EpisodeGuide.State.Upcoming }?.let { s.detailAirsOn(day(it, s)) }
            val line = listOfNotNull(length, airs).joinToString("  ·  ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (episode.state) {
                    EpisodeGuide.State.InChat -> Unit
                    EpisodeGuide.State.Missing -> StateBadge(s.detailMissingBadge, Tone.caution)
                    EpisodeGuide.State.Upcoming -> StateBadge(s.detailUpcoming, Tone.muted)
                }
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A small pill naming why a row cannot be played: missing from the chat, or not aired yet. */
@Composable
private fun StateBadge(label: String, tint: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.Small))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
