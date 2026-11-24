package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        val numbering = EpisodeGuide.numbering(series, extras)
        if (numbering.seasons.isEmpty() && numbering.next == null) return null
        if (!EpisodeGuide.agrees(series, numbering.seasons)) return null
        return EpisodeGuide.seasons(series, numbering.seasons, numbering.next, today).takeIf { it.isNotEmpty() }
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
        val numbering = EpisodeGuide.numbering(series, extras)
        val info = numbering.seasons.firstOrNull { it.number == shown.number }
        return EpisodeGuide.rows(shown.number, series, listed, today, info, numbering.next)
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
    /** A missing episode opens "Find in other chats"; an upcoming one has nothing to do and is no control. */
    onClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
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
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    // Dimmed like the rest of the page's gaps; only a row with something to do (a missing episode:
    // "Find in other chats") is a control, and wakes up while it holds the remote's focus.
    val control = onClick != null
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (focused) 1f else GAP_ALPHA)
            .clip(shape)
            .background(if (focused) Tone.focusFill else Tone.surface)
            .border(1.dp, if (focused) Color.Transparent else Tone.outline, shape)
            .then(if (control) Modifier.focusRing(focused, shape) else Modifier)
            .then(if (control && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (control) role = Role.Button
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactions,
                        indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(if (tv || large) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else if (tv) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(episodeArtWidth(large, tv))
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
                style = MaterialTheme.typography.titleMedium,
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
            // An anime counted straight through is named "Show - 123", not "S01E123": ask for its number.
            val code = if (episode.meta?.source.isNullOrBlank()) episode.code else episode.number.toString()
            add(MenuLine(s.seriesFindElsewhere, TmIcons.Search, null) { onDismiss(); onFind(findQuery(showTitle, code)) })
        }
    }
    val body = when (episode.state) {
        EpisodeGuide.State.Upcoming -> episode.airDate?.let { s.detailAirsOn(day(it, s)) } ?: s.detailUpcoming
        else -> s.seriesGapMissing(episode.code)
    }
    // Whose words the title is, where the episode's list is merged from several sites (an anime's).
    val source = episode.meta?.source?.takeIf { it.isNotBlank() }?.let { com.tmplayer.online.MetaWords.creditFrom(it) }
    MenuCard(title = title, subtitle = listOfNotNull(showTitle, source).joinToString("  ·  "), lines = lines, empty = body, onDismiss = onDismiss, cancel = s.commonClose)
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

private const val FOCUS_BACK_MS = 80L
