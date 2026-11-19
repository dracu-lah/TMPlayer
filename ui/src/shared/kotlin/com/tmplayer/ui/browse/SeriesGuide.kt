package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeGuide
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesSeason
import com.tmplayer.i18n.Messages
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/*
 * T9: the series page lists every season and episode the metadata knows, laid over the videos the
 * chats hold (core's EpisodeGuide does the merging). This file is the page's side of it, kept out
 * of SeriesPanel on purpose (see HX1 in Series.kt): the page's own method has no room for more
 * locals.
 */

/** What the series page needs from the merge, worked out in plain functions so a test can call them. */
internal object SeriesGuideRows {

    /** Today, as the providers write dates. */
    fun today(): String = java.time.LocalDate.now().toString()

    /**
     * The show's seasons as the metadata lists them with the chats' laid over, or null when the
     * page should list only what the chats hold: no metadata, a film, no season list, or a match
     * the chats disagree with ([EpisodeGuide.agrees]).
     */
    fun full(series: Series, meta: MetaInfo?, extras: MetaExtras?, today: String): List<EpisodeGuide.Season>? {
        if (meta?.kind != MetaKind.Show || extras == null) return null
        if (extras.seasonList.isEmpty() && extras.nextEpisode == null) return null
        if (!EpisodeGuide.agrees(series, extras.seasonList)) return null
        return EpisodeGuide.seasons(series, extras.seasonList, extras.nextEpisode, today).takeIf { it.isNotEmpty() }
    }

    /** The seasons the chats hold alone, as picker entries. */
    fun held(series: Series): List<EpisodeGuide.Season> {
        val episodes = series.seasons.map { EpisodeGuide.Season(it.number, it.episodes.size, null, EpisodeGuide.State.InChat) }
        // A season the chats hold only as a whole-season file is a season of the page too (TS).
        val packOnly = series.packs.map { it.season }.distinct()
            .filter { n -> series.seasons.none { it.number == n } }
            .map { EpisodeGuide.Season(it, 0, null, EpisodeGuide.State.InChat) }
        return if (packOnly.isEmpty()) episodes else (episodes + packOnly).sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
    }

    /** Whether [full] lists anything the chats lack: a season, or episodes of a season held in part. */
    fun hasGaps(full: List<EpisodeGuide.Season>?): Boolean =
        full != null && full.any { it.state != EpisodeGuide.State.InChat || (it.total != null && it.inChat < it.total!!) }

    /** The season to show: [wanted] when the picker lists it, else the first the chats hold. */
    fun shownNumber(wanted: Int, series: Series, stops: List<EpisodeGuide.Season>): Int =
        if (stops.any { it.number == wanted }) wanted else series.seasons.firstOrNull()?.number ?: stops.first().number

    /** The episode numbers [season] holds, for placing them in the provider's seasons ([com.tmplayer.online.EpisodeNumbering]). */
    fun numbers(season: SeriesSeason): Set<Int> = season.episodes.mapTo(HashSet()) { it.episode }

    /** [number] of [series] as its own season, empty for one only the metadata knows. */
    fun seasonOf(series: Series, number: Int): SeriesSeason =
        series.seasons.firstOrNull { it.number == number } ?: SeriesSeason(number, emptyList())

    /** The lines of the season shown: its videos alone, or with a gap where the metadata knows one. */
    fun rows(
        series: Series,
        shown: SeriesSeason,
        full: List<EpisodeGuide.Season>?,
        listed: List<MetaEpisode>?,
        extras: MetaExtras?,
        today: String,
    ): List<EpisodeGuide.Row> {
        if (full == null) {
            val have = shown.episodes.map { EpisodeGuide.Row.Have(it, null) }
            val packs = series.packs.filter { it.season == shown.number }
            return if (packs.isEmpty()) have else have + packs.map { EpisodeGuide.Row.Pack(it, shown.episodes.lastOrNull()?.episode ?: 0) }
        }
        val info = extras?.seasonList?.firstOrNull { it.number == shown.number }
        return EpisodeGuide.rows(shown.number, series, listed, today, info, extras?.nextEpisode)
    }
}

/** The episode number a line stands for, for the ranges and the fast scroller. */
internal val EpisodeGuide.Row.number: Int
    get() = when (this) {
        is EpisodeGuide.Row.Have -> episode.episode
        is EpisodeGuide.Row.Gap -> episode.number
        is EpisodeGuide.Row.Pack -> after
    }

/** "Season 3", or "Specials" for season 0. */
fun seasonName(number: Int, s: Messages): String = if (number == 0) s.seriesSpecials else s.seriesSeason(number)

/**
 * An episode the metadata lists and no chat holds, laid out as the card of an episode the chat
 * holds ([EpisodeRow]) and dimmed: its still (else its number), its data title, its air date, and a
 * small tag saying why it does not play. It is not a control: no click, no focus (a remote's Up and
 * Down move over it to the next episode that plays), and no Play. A screen reader reads one line,
 * "Episode 7, not in this chat".
 */
@Composable
fun GapRow(
    episode: EpisodeGuide.Episode,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val shape = RoundedCornerShape(Corner.Medium)
    val meta = episode.meta
    // A placeholder the provider writes for an episode nobody has named ("Episode 5") is no name.
    val name = meta?.name?.trim()?.takeIf { it.isNotEmpty() && !com.tmplayer.online.EpisodeNames.placeholder(it, episode.number) }
        ?: s.seriesEpisode(episode.number)
    val upcoming = episode.state == EpisodeGuide.State.Upcoming
    val airs = remember(episode.airDate, s) { episode.airDate?.let { day(it, s) } }
    val tag = if (upcoming) airs?.let { s.detailAirsOn(it) } ?: s.detailUpcoming else s.seriesGapNotInChat
    val spoken = if (upcoming) s.seriesGapSpokenUpcoming(episode.number, airs ?: s.detailUpcoming) else s.seriesGapSpokenMissing(episode.number)
    val facts = listOfNotNull(
        meta?.runtimeMin?.takeIf { it in 1..GAP_MAX_RUNTIME_MIN }?.let { MediaMapper.formatDuration(it * 60).ifEmpty { null } },
        // An upcoming one says its day in the tag already.
        airs.takeIf { !upcoming },
    ).joinToString("  ·  ")
    val overview = meta?.overview?.trim()?.takeIf { it.isNotEmpty() }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(GAP_ALPHA)
            .clip(shape)
            .background(Tone.surface)
            .border(1.dp, Tone.outline, shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(if (tv || large) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else if (tv) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (large) GAP_ART_LARGE else if (tv) GAP_ART_TV else GAP_ART_TOUCH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Corner.Small))
                .background(Tone.surfaceHigh),
        ) {
            Text(
                episode.number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = Tone.muted,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
            EpisodeStill(meta?.stillUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.seriesEpisodeShort(episode.season, episode.number),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.muted,
                    maxLines = 1,
                )
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Tone.surfaceHigh)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
            Text(
                name,
                style = if (tv || large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = Tone.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (facts.isNotEmpty()) {
                Text(
                    facts,
                    style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (overview != null) {
                Text(
                    overview,
                    style = if (tv || large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** What the page dims an episode the chat lacks to (about 45%): text, still and card together. */
internal const val GAP_ALPHA = 0.45f

private const val GAP_MAX_RUNTIME_MIN = 24 * 60
private val GAP_ART_TOUCH = 128.dp
private val GAP_ART_TV = 176.dp
private val GAP_ART_LARGE = 208.dp

/**
 * A season, or several, posted as one file ([com.tmplayer.data.SeriesPack]): a card that plays the
 * file from its first episode, after the season's episodes. It looks like an episode's card (it is
 * a video the chats hold) but says what it is and never carries an episode's number.
 */
@Composable
internal fun PackRow(
    pack: com.tmplayer.data.SeriesPack,
    large: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val title = packTitle(pack, s)
    val facts = remember(pack.item.id) { com.tmplayer.data.EpisodeCopies.label(pack.item) }
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (tv || large) 72.dp else 64.dp)
            .clip(shape)
            .background(if (focused) Tone.focusFill else Tone.surface)
            .border(1.dp, if (focused) Color.Transparent else Tone.outline, shape)
            .focusRing(focused, shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = if (tv || large) 16.dp else 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv || large) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Play, contentDescription = null, tint = if (focused) ink else Tone.accent, modifier = Modifier.size(if (tv || large) 28.dp else 24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (tv || large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (facts.isNotBlank()) {
                Text(facts, style = MaterialTheme.typography.labelMedium, color = if (focused) ink else Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                s.seriesPackNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) ink else Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Whole season", or "Seasons 1 to 3, whole" for a pack of a span. */
internal fun packTitle(pack: com.tmplayer.data.SeriesPack, s: Messages): String =
    pack.seasonEnd?.takeIf { it > pack.season }?.let { s.seriesPackSpan(pack.season, it) } ?: s.seriesPackWhole

/** "Harbour Notes S02E04": the show and the code, as a file or a caption writes them. */
internal fun findQuery(showTitle: String, code: String): String = "$showTitle $code".trim()

/**
 * Over a season the chats hold none of: it is not in them yet, and one way to look elsewhere. Drawn
 * above the season's quiet rows, or alone where the metadata gave the season no episodes.
 */
@Composable
internal fun SeasonGapNote(number: Int, showTitle: String, state: EpisodeGuide.State, airDate: String?, onFind: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(Corner.Medium)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tone.surface)
            .border(1.dp, Tone.outline, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val note = if (state == EpisodeGuide.State.Upcoming) {
            airDate?.let { s.detailAirsOn(day(it, s)) } ?: s.detailUpcoming
        } else {
            s.seriesSeasonGapNote(seasonName(number, s))
        }
        Text(note, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(vertical = 8.dp))
        if (state == EpisodeGuide.State.Missing && onFind != null) {
            MenuRow(s.seriesFindElsewhere, TmIcons.Search, null, { onFind(findQuery(showTitle, "S%02d".format(java.util.Locale.ROOT, number))) }, Modifier)
        }
    }
}
