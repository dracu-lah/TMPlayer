package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PosterPick
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SiblingVideos
import com.tmplayer.data.VideoCorrection
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * "Choose the right poster": what the phone's sheet, the television's panel and the desktop's
 * dialog share. Each draws its own list (a sheet, a remote-driven panel, a dialog); the question,
 * the answer's states, the facts line and the marks are one set of rules, here.
 */

/** What the picker shows: waiting for TMDB, its titles, or a plain message (with Try again where trying again may help). */
sealed interface PickerLoad {
    data object Loading : PickerLoad
    data class Ready(val choices: List<PosterChoice>) : PickerLoad
    data class Message(val text: String, val canRetry: Boolean) : PickerLoad
}

/**
 * The question the picker asks for [item]: the viewer's own title where they renamed the video,
 * else the cleaned name the lookup uses, else (a name that asks nothing) what the tile calls it.
 * Never carries a pick: the search is for the name.
 */
fun posterPickerQuery(item: MediaItem, correction: VideoCorrection?): MetaQuery? {
    val renamed = correction?.title
    val base = if (renamed != null) MetaQuery.of(renamed, null) else item.metaQuery
    val named = base ?: SeriesShelf.filmTitle(item).trim().takeIf { it.isNotEmpty() }?.let { MetaQuery(title = it) }
    return named?.copy(tmdbId = null)
}

/**
 * The titles TMDB has for [query], once the picker is open. A new [attempt] asks again (Try
 * again). Asked off the main thread; a failure of any kind is a message, never an exception.
 */
@Composable
fun rememberPosterChoices(query: MetaQuery?, attempt: Int): PickerLoad {
    val s = LocalStrings.current
    val online = OnlineMetadata.current
    val load by produceState<PickerLoad>(PickerLoad.Loading, query, attempt, online) {
        value = PickerLoad.Loading
        if (online == null || query == null) {
            value = if (online == null) PickerLoad.Message(s.posterOff, canRetry = false) else PickerLoad.Ready(emptyList())
            return@produceState
        }
        val answer = withContext(Dispatchers.IO) { runCatching { online.candidates(query) }.getOrNull() }
        value = when (answer) {
            is PosterChoices.Found -> PickerLoad.Ready(answer.choices)
            PosterChoices.Off -> PickerLoad.Message(s.posterOff, canRetry = false)
            PosterChoices.NoKey -> PickerLoad.Message(s.posterNoKey, canRetry = false)
            PosterChoices.Unavailable, null -> PickerLoad.Message(s.posterFailed, canRetry = true)
        }
    }
    return load
}

/** Files of [item]'s title in its chat beyond its own copies: the others a pick may be offered to. */
@Composable
fun rememberSameTitleOthers(item: MediaItem): List<MediaItem> {
    val others by produceState(emptyList<MediaItem>(), item.id) {
        value = withContext(Dispatchers.Default) { runCatching { SiblingVideos.sameTitle(item) }.getOrDefault(emptyList()) }
    }
    return others
}

/** "2022  ·  Film  ·  Tamil": what tells two titles of one name apart. */
@Composable
fun posterChoiceFacts(choice: PosterChoice): String {
    val s = LocalStrings.current
    val language = choice.originalLanguage.takeIf { it.isNotBlank() }?.let { code ->
        val locale = java.util.Locale.getDefault()
        runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) } }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: code.uppercase()
    }
    return listOfNotNull(choice.year?.toString(), if (choice.show) s.posterShow else s.posterFilm, language).joinToString("  ·  ")
}

/** The two lines under a title: its facts, then a line of its story. */
@Composable
fun posterChoiceDetail(choice: PosterChoice, current: Boolean): String {
    val s = LocalStrings.current
    val facts = posterChoiceFacts(choice)
    return listOfNotNull(
        (if (current) "${s.posterCurrent}  ·  $facts" else facts),
        choice.overview.takeIf { it.isNotBlank() },
    ).joinToString("\n")
}

/**
 * Whether [choice] is the title the video wears now: the one the tile's match is, or the one the
 * viewer picked before (also when TMDB no longer answers for it, so the mark does not vanish).
 */
fun isCurrentChoice(choice: PosterChoice, shown: MetaInfo?, correction: VideoCorrection?): Boolean =
    choice.isMatch(shown) || correction?.pick == choice.pick

/** A candidate's small poster, 2:3, in a rounded frame; a blank frame for a title with none. */
@Composable
fun PosterChoiceThumb(choice: PosterChoice, width: Dp = 48.dp, modifier: Modifier = Modifier) {
    val frame = remember { RoundedCornerShape(6.dp) }
    Box(modifier.width(width).aspectRatio(2f / 3f).clip(frame).background(Tone.surface)) {
        MetaPicture(choice.posterUrl, Modifier.matchParentSize(), maxWidth = 160)
    }
}

/** What the pick is written as: [pick] on [files], or (null) back to automatic. */
fun correctionFor(pick: PosterPick?): (VideoCorrection) -> VideoCorrection =
    { if (pick == null) it.resetMatch() else it.withPick(pick) }
