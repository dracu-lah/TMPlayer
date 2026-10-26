package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.App
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeRow
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TvChannels
import com.tmplayer.data.TvChannelsPublisher
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A show a card on the TV home screen asked for, waiting for Home to open its page. */
object HomeLaunch {
    val show = MutableStateFlow<TvChannels.Target.Show?>(null)

    /** How long the ask waits for the show to turn up in Home's rows. */
    const val WAIT_MS = 60_000L

    /** The show [ask] names among [rows], or null while it is not there (yet). */
    fun find(ask: TvChannels.Target.Show, rows: List<HomeRow>): com.tmplayer.data.Series? =
        find(ask.seriesKey, ask.chatId, rows)

    /** The show with [seriesKey] in [chatId] among [rows], or null while it is not there (yet). */
    fun find(seriesKey: String, chatId: Long, rows: List<HomeRow>): com.tmplayer.data.Series? =
        rows.asSequence()
            .flatMap { row ->
                when (row) {
                    is HomeRow.Recent -> row.entries.asSequence()
                    is HomeRow.Chat -> row.entries.asSequence()
                    else -> emptySequence()
                }
            }
            .filterIsInstance<com.tmplayer.data.ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { it.key == seriesKey && it.cover.chatId == chatId }
}

/**
 * Keeps TMPlayer's channels on the Android TV home screen in step with Home's [rows] (see
 * [TvChannels] for what goes in them, [TvChannelsPublisher] for how and when). Draws nothing, and
 * does nothing at all on a phone, a Fire TV or the promo build's fixture-only screens.
 *
 * - Asks Home for Recently added as soon as the viewer is signed in, so the channel fills even
 *   when Home itself is not opened. Home picks its own chats, so nothing needs starring.
 * - Waits for Home's rows to be built from an answer ([HomeViewModel.rowsSettled]): the empty rows
 *   of a cold start would otherwise empty the channel until Home had loaded.
 * - Writes half a minute after the rows settle, so a Home filling row by row is one write, made
 *   after its pictures rather than among them.
 * - Takes every card out when the kids lock goes on, then puts back what the lock allows; empties the
 *   channels when the setting is turned off.
 *
 * @param enabled the Settings switch; null until it has been read, which does nothing either way.
 */
@Composable
fun TvHomeChannels(
    model: HomeViewModel,
    rows: List<HomeRow>,
    chats: List<ChatSummary>,
    watchProgress: Map<String, WatchPoint>,
    signedIn: Boolean,
    enabled: Boolean?,
) {
    val context = LocalContext.current.applicationContext
    val supported = remember { TvChannelsPublisher.supported(context) }
    if (!supported) return
    val allowed by ChatGate.allowed.collectAsStateWithLifecycle()
    val art by model.art.collectAsStateWithLifecycle()
    val settled = model.rowsSettled

    // The last plan written, so the same cards are not written again; forgotten whenever the
    // channels are emptied, so they are filled again after.
    val written = remember { arrayOfNulls<List<TvChannels.Program>>(1) }

    // "Play next" entries written before their picture was on the device wear the app's mark; once
    // Home has been up a while, and never under a playing video, they are given their own picture.
    // Once per Home, a few entries at a time: it stops by itself.
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        delay(SETTLE_MS)
        while (com.tmplayer.data.WatchCache.anyPlaying()) delay(PLAYING_RECHECK_MS)
        runCatching { com.tmplayer.data.WatchNextPublisher.fillMissingArt(context) }
    }

    // Off empties the channels rather than deleting them. The launcher hides an empty channel, and
    // keeps the place the viewer gave it: Android lets an app's first channel onto the home screen
    // without asking, but a channel made again after a delete waits in the launcher's settings.
    LaunchedEffect(enabled) {
        if (enabled == false) written[0] = null
        if (enabled == false) App.backgroundScope.launch { TvChannelsPublisher.clearPrograms(context) }
    }

    // What the default channel is drawn from, asked for whether or not Home is on screen: the
    // search across chats, from which Home picks its chats and builds Recently added. Asked once
    // per Home, so this costs nothing once Home has asked for itself.
    LaunchedEffect(enabled, signedIn) {
        if (enabled != true || !signedIn) return@LaunchedEffect
        model.requestRecent()
    }
    // Watch later's records keep no picture; Home looks each one up for its tile, and so do the cards.
    val later = remember(rows) { rows.filterIsInstance<HomeRow.WatchLater>().firstOrNull()?.records.orEmpty() }
    LaunchedEffect(enabled, signedIn, later) {
        if (enabled != true || !signedIn) return@LaunchedEffect
        later.forEach(model::requestArt)
    }

    // The kids lock going on clears the cards outright, before the plan below puts back the ones
    // from the allowed chats: nothing from the other chats may sit on the home screen meanwhile.
    val locked = allowed != null
    LaunchedEffect(locked, enabled) {
        if (locked && enabled == true) written[0] = null
        if (locked && enabled == true) App.backgroundScope.launch { TvChannelsPublisher.clearPrograms(context) }
    }

    // The plan is worked out off the main thread, and only once the rows have been still for
    // [SETTLE_MS]: worked out in composition it ran on every chat, picture and progress change,
    // all of them while Home was opening and its posters were being decoded.
    LaunchedEffect(rows, settled, allowed, art, watchProgress, chats, enabled, signedIn) {
        if (enabled != true || !signedIn || !settled) return@LaunchedEffect
        // Rows arrive one after another as Home fills; the write waits for them to settle.
        delay(SETTLE_MS)
        // Never while a video plays: the write uploads every card's picture to the launcher, and
        // on a 1 GB stick that is decoding and a binder storm under the player. A wait already
        // under way when Play was pressed carries on once the player has closed.
        if (com.tmplayer.data.WatchCache.anyPlaying()) {
            while (com.tmplayer.data.WatchCache.anyPlaying()) delay(PLAYING_RECHECK_MS)
            delay(SETTLE_MS)
        }
        val plan = withContext(Dispatchers.Default) {
            runCatching {
                val titles = chats.associate { it.id to it.title }
                TvChannels.plan(
                    rows = rows,
                    allowed = allowed,
                    chatTitle = { titles[it].orEmpty() },
                    progress = { chat, message -> watchProgress[SettingsStore.progressKey(chat, message)] },
                    art = art,
                )
            }.getOrNull()
        } ?: return@LaunchedEffect
        if (plan == written[0]) return@LaunchedEffect
        written[0] = plan
        App.backgroundScope.launch {
            TvChannelsPublisher.publish(context, plan) { channel ->
                when (channel) {
                    TvChannels.Channel.Recent -> L.homeChannelRecent
                    TvChannels.Channel.WatchLater -> L.homeRowWatchLater
                    TvChannels.Channel.NewEpisodes -> L.homeRowNewEpisodes
                }
            }
        }
    }
}

/**
 * How long Home's rows stay still before the channels are written: long enough that the write
 * never competes with the posters of a Home that is still opening (it was 2.5 s).
 */
private const val SETTLE_MS = 30_000L

/** How often a channel write held back by a video playing looks again. */
private const val PLAYING_RECHECK_MS = 15_000L
