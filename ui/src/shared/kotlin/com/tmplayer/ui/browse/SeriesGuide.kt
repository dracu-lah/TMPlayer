package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeGuide
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
import kotlinx.coroutines.delay

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
    fun held(series: Series): List<EpisodeGuide.Season> =
        series.seasons.map { EpisodeGuide.Season(it.number, it.episodes.size, null, EpisodeGuide.State.InChat) }

    /** Whether [full] lists anything the chats lack: a season, or episodes of a season held in part. */
    fun hasGaps(full: List<EpisodeGuide.Season>?): Boolean =
        full != null && full.any { it.state != EpisodeGuide.State.InChat || (it.total != null && it.inChat < it.total!!) }

    /** The season to show: [wanted] when the picker lists it, else the first the chats hold. */
    fun shownNumber(wanted: Int, series: Series, stops: List<EpisodeGuide.Season>): Int =
        if (stops.any { it.number == wanted }) wanted else series.seasons.first().number

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
        if (full == null) return shown.episodes.map { EpisodeGuide.Row.Have(it, null) }
        val info = extras?.seasonList?.firstOrNull { it.number == shown.number }
        return EpisodeGuide.rows(shown.number, series, listed, today, info, extras?.nextEpisode)
    }
}

/** The episode number a line stands for, for the ranges and the fast scroller. */
internal val EpisodeGuide.Row.number: Int
    get() = when (this) {
        is EpisodeGuide.Row.Have -> episode.episode
        is EpisodeGuide.Row.Gap -> episode.number
    }

/** "Season 3", or "Specials" for season 0. */
fun seasonName(number: Int, s: Messages): String = if (number == 0) s.seriesSpecials else s.seriesSeason(number)

/**
 * An episode the metadata lists and no chat holds: quiet, with its data title and a plain line
 * saying why it does not play. It is a control, not a decoration: pressed it says what it is
 * ([GapCard]); it never plays.
 */
@Composable
internal fun GapRow(
    episode: EpisodeGuide.Episode,
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
    val name = episode.meta?.name?.trim()?.takeIf { it.isNotEmpty() } ?: s.seriesEpisode(episode.number)
    val why = gapLine(episode, s)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (tv || large) 72.dp else 64.dp)
            .alpha(if (focused) 1f else QUIET)
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
        Text(
            s.seriesEpisodeShort(episode.season, episode.number),
            style = if (tv || large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = if (focused) ink else Tone.muted,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = if (tv || large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) ink else Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun gapLine(episode: EpisodeGuide.Episode, s: Messages): String = when (episode.state) {
    EpisodeGuide.State.Upcoming -> episode.airDate?.let { s.detailAirsOn(day(it, s)) } ?: s.detailUpcoming
    else -> s.seriesNotInChats
}

/**
 * What a pressed gap says: what the episode is, that it is not in the chats (or when it airs), and
 * for a missing one a single way on, "Find in other chats", which searches the show and its code.
 * Back or Close leaves everything as it was.
 */
@Composable
internal fun GapCard(
    episode: EpisodeGuide.Episode,
    showTitle: String,
    onFind: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val title = listOfNotNull(episode.code, episode.meta?.name?.trim()?.takeIf { it.isNotEmpty() }).joinToString("  ")
    val lines = buildList {
        if (episode.state == EpisodeGuide.State.Missing && onFind != null) {
            add(MenuLine(s.seriesFindElsewhere, TmIcons.Search, null) { onDismiss(); onFind(findQuery(showTitle, episode.code)) })
        }
    }
    val body = when (episode.state) {
        EpisodeGuide.State.Upcoming -> episode.airDate?.let { s.detailAirsOn(day(it, s)) } ?: s.detailUpcoming
        else -> s.seriesGapMissing(episode.code)
    }
    MenuCard(title = title, subtitle = showTitle, lines = lines, empty = body, onDismiss = onDismiss, cancel = s.commonClose)
}

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

/**
 * Which gap is open on the page, and where the focus goes back to when its card closes (a remote
 * leaves the card on the row it opened it from). One per page.
 */
internal class GapPrompt {
    var open by mutableStateOf<EpisodeGuide.Episode?>(null)
    private var refocus: (() -> Unit)? = null

    fun show(episode: EpisodeGuide.Episode, back: () -> Unit) {
        refocus = back
        open = episode
    }

    fun close() {
        open = null
    }

    fun refocusNow() {
        refocus?.invoke()
    }
}

/** The card for [prompt]'s open gap, and the focus back on its row after it closes. */
@Composable
internal fun GapPromptHost(prompt: GapPrompt, showTitle: String, onFind: ((String) -> Unit)?) {
    val episode = prompt.open
    if (episode != null) GapCard(episode, showTitle, onFind) { prompt.close() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(episode == null, wasOpen) {
        if (episode != null) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            // The dialog's window gives the focus back a frame or two after it closes.
            delay(FOCUS_BACK_MS)
            prompt.refocusNow()
        }
    }
}

private const val QUIET = 0.62f
private const val FOCUS_BACK_MS = 80L
