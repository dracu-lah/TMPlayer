package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.tmplayer.data.HomeRow
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.OfflineHomeRows
import com.tmplayer.data.OfflineSettle
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Td
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.runCatchingCancellable
import com.tmplayer.ui.components.TileBadge
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline Home (R4): whether the line is down (already settled, see [OfflineSettle]), which videos
 * are in Downloads, and, once [OfflineHomeScope] has asked, which others are complete in the watch
 * cache. The shell provides the first two; Home adds the third for the cards under it. Online it is
 * [OfflineHome.Online] and nothing is greyed.
 */
val LocalOfflineHome = staticCompositionLocalOf { OfflineHome.Online }

/**
 * [raw] (true while the line is down) held for [OfflineSettle.HOLD_MS] each way, so a flaky link
 * never flashes the cards grey and back. Starts from [raw], so an app opened offline says so at once.
 */
@Composable
fun rememberSettledOffline(raw: Boolean): Boolean {
    val latest = rememberUpdatedState(raw)
    return produceState(raw) {
        OfflineSettle.settled(snapshotFlow { latest.value }, start = raw).collect { value = it }
    }.value
}

/**
 * Gives [content] Home's rows as an offline Home draws them ([OfflineHomeRows]) with
 * [LocalOfflineHome] filled in, for its cards to grey themselves by. Asks the watch cache about at
 * most [OfflineHomeRows.CACHE_CHECKS] videos, once per offline stretch, off the main thread.
 */
@Composable
fun OfflineHomeScope(rows: List<HomeRow>, content: @Composable (List<HomeRow>) -> Unit) {
    val shell = LocalOfflineHome.current
    if (!shell.offline) {
        content(rows)
        return
    }
    val candidates = remember(rows, shell.downloads) { OfflineHomeRows.cacheCandidates(rows, shell.downloads) }
    // Null until the watch cache has answered the first time; later asks keep the last answer
    // meanwhile, so the cards do not go back to a skeleton for a row that changed.
    val cachedOrNull by produceState<Set<String>?>(null, candidates) {
        value = if (candidates.isEmpty()) emptySet() else withContext(Dispatchers.Default) {
            candidates.filter { (_, fileId) ->
                runCatchingCancellable { Td.localFileAvailability(fileId) }.getOrDefault(LocalFileAvailability.Missing) ==
                    LocalFileAvailability.Complete
            }.mapTo(HashSet()) { it.first }
        }
    }
    val settled = shell.settled && cachedOrNull != null
    val home = remember(shell, cachedOrNull, settled) {
        OfflineHome(true, shell.downloads, shell.cached + cachedOrNull.orEmpty(), settled)
    }
    // Held, not drawn: a frame built before both reads finish would grey every card that is in fact
    // playable and could put the remote's first focus on one. Home shows its skeleton meanwhile.
    val shown = remember(rows, home) { if (home.settled) OfflineHomeRows.apply(rows, home) else emptyList() }
    CompositionLocalProvider(LocalOfflineHome provides home) { content(shown) }
}

/**
 * A press on a video that opens [open] when it plays, and [details] (its page, which says why not)
 * when offline it cannot: never a toast after the press, and never a dead one.
 */
fun OfflineHome.gate(open: (MediaItem, String) -> Unit, details: (MediaItem, String) -> Unit): (MediaItem, String) -> Unit =
    { item, chat -> if (plays(item)) open(item, chat) else details(item, chat) }

fun OfflineHome.gateResume(open: (ResumeRecord) -> Unit, details: (MediaItem, String) -> Unit): (ResumeRecord) -> Unit =
    { record -> if (plays(record.chatId, record.messageId)) open(record) else details(record.toMediaItem(), record.chatTitle) }

fun OfflineHome.gateLater(open: (WatchLaterRecord) -> Unit, details: (MediaItem, String) -> Unit): (WatchLaterRecord) -> Unit =
    { record ->
        if (record.isShow || plays(record.chatId, record.messageId)) open(record) else details(record.toMediaItem(), record.chatTitle)
    }

/**
 * Over a tile's art when it cannot play with no line: the picture dimmed and "Needs internet" in
 * the top start corner, where "Downloaded" would be. The tile stays focusable and pressable; its
 * press opens the page.
 */
@Composable
fun BoxScope.NeedsInternetCover() {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().background(Tone.background.copy(alpha = DIM_ALPHA)))
    TileBadge(s.homeNeedsInternet, Modifier.align(Alignment.TopStart).padding(TileBadgeInset))
}

/** The same for a screen reader, which has no picture to see dimmed. */
fun Modifier.needsInternetSemantics(on: Boolean, label: String): Modifier =
    if (on) semantics { stateDescription = label } else this

private const val DIM_ALPHA = 0.62f
