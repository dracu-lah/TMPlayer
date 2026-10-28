package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.tmplayer.data.Series
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShowEdits
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * [ShowActions] for [series] on the stores every platform shares (HX20): the phone, the television
 * and the desktop each hand in their own [settings] and [watched], how they say a short line
 * ([onMessage]), and the way to the show's chat when the page or menu was opened outside it.
 *
 * Every write is caught: a store that fails says so in a line and leaves the page as it was.
 */
@Composable
fun rememberShowActions(
    series: Series,
    watch: SeriesWatch,
    settings: SettingsStore,
    watched: WatchedStore,
    chatTitle: String,
    onMessage: (String) -> Unit,
    onOpenChat: (() -> Unit)? = null,
    /**
     * Where the writes run: the process's by default, since a menu closes as its line is picked and
     * would take a scope of its own with it before the write was done.
     */
    scope: kotlinx.coroutines.CoroutineScope = com.tmplayer.platform.Background.scope,
): ShowActions {
    val s = LocalStrings.current

    val chatId = series.cover.chatId
    val saved by remember(watched) { watched.watchLater.showIds }.collectAsState(initial = emptySet())
    val onList = WatchLaterRecord.showId(chatId, series.key) in saved
    val currentSeries by rememberUpdatedState(series)
    val currentWatch by rememberUpdatedState(watch)
    val say by rememberUpdatedState(onMessage)
    return remember(series.key, chatId, onList, chatTitle, onOpenChat, settings, watched, s) {
        ShowActions(
            watchLater = onList,
            onWatchLater = { add ->
                val show = currentSeries
                scope.launch {
                    val done = runCatching {
                        if (add) {
                            watched.watchLater.add(WatchLaterRecord.ofShow(show, chatTitle, System.currentTimeMillis()))
                        } else {
                            watched.watchLater.removeShow(chatId, show.key)
                        }
                    }
                    done.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { say(
                        when {
                            done.isFailure -> s.errorsScreenFailedTitle
                            add -> s.detailWatchLaterAdded(show.title)
                            else -> s.detailWatchLaterRemoved(show.title)
                        },
                    ) }
                }
            },
            onMark = { choice ->
                val show = currentSeries
                val w = currentWatch
                val progress = w.progress(show)
                // Everything left of the show, whichever choice said it: watched whole, it leaves Watch later
                // too, as a finished video does.
                val finishesShow = choice.watched && show.episodes.count { !w.finishedEpisode(it) } <= choice.count
                scope.launch {
                    val done = runCatching {
                        ShowEdits.apply(settings, watched, choice, chatTitle, System.currentTimeMillis()) { w.pick(it, progress) }
                    }
                    done.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    val count = done.getOrNull()
                    if (count != null && finishesShow) {
                        runCatching { watched.watchLater.removeShow(chatId, show.key) }
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { say(
                        when {
                            count == null -> s.errorsScreenFailedTitle
                            choice.watched -> s.seriesMarkedWatched(count)
                            else -> s.seriesMarkedUnwatched(count)
                        },
                    ) }
                }
            },
            onOpenChat = onOpenChat,
            chatTitle = chatTitle,
        )
    }
}
