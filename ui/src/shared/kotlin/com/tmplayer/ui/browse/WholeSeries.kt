package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesFill
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.platform.LruMap
import kotlinx.coroutines.CancellationException

/**
 * A show's page with every episode its chat holds, not only those the screen it was opened from
 * had read ([SeriesFill]): [series] on the first frame, then the show made whole once its chat has
 * been searched for it, a page at a time up to [SeriesFill.MAX_PAGES]. Kept per show for the run,
 * so opening it again draws it whole at once and asks again only behind it.
 */
@Composable
fun rememberWholeSeries(series: Series): Series {
    val whole by produceState(WholeSeries.peek(series) ?: series, series) {
        val read = runCatching { WholeSeries.read(series) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            null
        }
        if (read != null && read != value) value = read
    }
    return whole
}

/** The searches behind [rememberWholeSeries], and what they found, by show. */
object WholeSeries {
    private val kept = LruMap<String, Series>(KEPT)

    /** [series] as made whole before, when that still holds every episode it has now. */
    fun peek(series: Series): Series? =
        kept[keyOf(series)]?.takeIf { it.episodeCount >= series.episodeCount && it.key == series.key }

    /**
     * Searches [series]' chat for it ([fetch], Telegram by default), within Home's size floor so a short sample is not taken for an episode, and folds in what belongs to it.
     */
    suspend fun read(
        series: Series,
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors, topicId: Int) -> MediaPage? = ::telegram,
    ): Series {
        val query = SeriesFill.queryOf(series) ?: return series
        val chatId = series.cover.chatId
        val topic = series.cover.topicId
        val found = ArrayList<MediaItem>()
        var cursors = MediaCursors()
        for (page in 0 until SeriesFill.MAX_PAGES) {
            val read = fetch(chatId, query, cursors, topic) ?: break
            found += read.items.filter { SizeFilter.matches(it.sizeBytes, SizeFilter.HOME_MIN, SizeFilter.DEFAULT_MAX) }
            if (read.endReached || read.cursors.allDone) break
            cursors = read.cursors
        }
        val whole = SeriesFill.merge(peek(series) ?: series, found)
        kept[keyOf(series)] = whole
        return whole
    }

    private suspend fun telegram(chatId: Long, query: String, cursors: MediaCursors, topicId: Int): MediaPage? {
        val session = Td.awaitConnectedSessionOrNull() ?: return null
        return ChatRepository(session.client).mediaPage(chatId, cursors, query = query, pageSize = SeriesFill.PAGE, topicId = topicId)
    }

    private fun keyOf(series: Series) = "${series.cover.chatId}|${series.key}"

    /** Shows kept made whole: a run's worth of pages opened. */
    private const val KEPT = 48
}
