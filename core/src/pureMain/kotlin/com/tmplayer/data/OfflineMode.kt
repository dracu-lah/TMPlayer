package com.tmplayer.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.channelFlow

/**
 * The offline answer, held steady (R4): a flaky link that flips offline and online must not flash
 * Home's cards grey and back. A change is passed on only once the line has said the same thing for
 * [HOLD_MS]; a flip back inside that time cancels it and nothing shows.
 */
object OfflineSettle {

    /** How long the line must hold a state before the screen believes it, both ways. */
    const val HOLD_MS = 2_000L

    /** [raw] (true while the line is down) as the screen should show it, starting from [start]. */
    fun settled(raw: Flow<Boolean>, start: Boolean = false, holdMs: Long = HOLD_MS): Flow<Boolean> = channelFlow {
        var shown = start
        send(shown)
        raw.collectLatest { now ->
            if (now == shown) return@collectLatest
            delay(holdMs)
            shown = now
            send(now)
        }
    }
}

/**
 * What Home knows about the line and the device: [offline] (already settled, see [OfflineSettle]),
 * the videos in Downloads that are really there, and the keys of Continue watching and Watch later
 * videos found complete in the watch cache. Only those two groups play without a line.
 *
 * [downloads] and [cached] are read once per offline stretch and are empty while online.
 *
 * [settled] is false while those reads are still running, so a frame built before them is held
 * (a skeleton) instead of drawn with every card grey and the remote's first focus on one of them.
 */
class OfflineHome(
    val offline: Boolean = false,
    val downloads: List<ResumeRecord> = emptyList(),
    val cached: Set<String> = emptySet(),
    val settled: Boolean = true,
) {
    /** The keys ([ProgressKeys.of]) of the videos in Downloads, which say Downloaded offline. */
    val downloadedKeys: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        downloads.mapTo(HashSet(downloads.size)) { ProgressKeys.of(it.chatId, it.messageId) }
    }

    /** The keys of everything that plays with no line: the downloads and the cached videos. */
    val playableKeys: Set<String> by lazy(LazyThreadSafetyMode.NONE) { downloadedKeys + cached }

    /** Whether a press on this video may start it now. Always true while online. */
    fun plays(chatId: Long, messageId: Long): Boolean = !offline || ProgressKeys.of(chatId, messageId) in playableKeys

    fun plays(item: MediaItem): Boolean =
        !offline || item.locality != MediaItem.Locality.Remote || plays(item.chatId, item.messageId)

    /** True for a card that must be greyed and say it needs the internet. */
    fun needsInternet(item: MediaItem): Boolean = offline && !plays(item)

    companion object {
        val Online = OfflineHome()
    }
}

/**
 * Home's rows as an offline Home draws them (R4), one rule for the phone, the television and the
 * desktop:
 * - "On this phone" leads: Continue watching videos that play with no line, newest first, then the
 *   downloads that are not already there. A video is in one place only, so those records leave the
 *   ordinary Continue row (which is dropped when nothing is left in it).
 * - Rows still waiting for Telegram are left out: offline they could only be placeholders for ever.
 * - Everything else stays, to be greyed by the tiles themselves ([OfflineHome.needsInternet]).
 *
 * Online this is the identity.
 */
object OfflineHomeRows {

    /** At most this many videos are asked about in the watch cache per offline stretch. */
    const val CACHE_CHECKS = 40

    fun apply(rows: List<HomeRow>, home: OfflineHome): List<HomeRow> {
        if (!home.offline) return rows
        val continueRow = rows.firstNotNullOfOrNull { it as? HomeRow.Continue }
        val playing = continueRow?.records.orEmpty().filter { home.plays(it.chatId, it.messageId) }
        val seen = playing.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) }
        val downloaded = home.downloads
            .filter { seen.add(ProgressKeys.of(it.chatId, it.messageId)) }
            .sortedByDescending { it.updatedAt }
        val phone = playing + downloaded
        val lead = if (phone.isEmpty()) {
            null
        } else {
            HomeRow.Continue(records = phone.take(HomeRows.LIMIT), more = phone.drop(HomeRows.LIMIT), onPhone = true)
        }
        val rest = rows.mapNotNull { row ->
            when {
                row is HomeRow.Continue && !row.onPhone -> {
                    val left = row.records.filterNot { home.plays(it.chatId, it.messageId) }
                    val more = row.more.filterNot { home.plays(it.chatId, it.messageId) }
                    if (left.isEmpty() && more.isEmpty()) null else row.copy(records = left, more = more)
                }
                row is HomeRow.Chat && !row.loaded -> null
                row is HomeRow.Recent && !row.loaded -> null
                else -> row
            }
        }
        return listOfNotNull(lead) + rest
    }

    /**
     * The video offline Home's hero shows instead of the carousel: the first of "On this phone", or
     * null when nothing plays (no hero then, rather than a picture of something that cannot play).
     */
    fun hero(rows: List<HomeRow>): ResumeRecord? =
        rows.firstNotNullOfOrNull { it as? HomeRow.Continue }?.takeIf { it.onPhone }?.records?.firstOrNull()

    /**
     * The videos whose watch cache is worth asking about, as (key, file id): Continue watching and
     * Watch later, the ones not already known to be downloads, in row order, at most [CACHE_CHECKS].
     */
    fun cacheCandidates(rows: List<HomeRow>, downloads: List<ResumeRecord>): List<Pair<String, Int>> {
        val have = downloads.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) }
        val out = LinkedHashMap<String, Int>()
        for (row in rows) {
            val found: List<Pair<String, Int>> = when (row) {
                is HomeRow.Continue -> (row.records + row.more).map { ProgressKeys.of(it.chatId, it.messageId) to it.fileId }
                is HomeRow.WatchLater -> (row.records + row.more).map { it.key to it.fileId }
                else -> emptyList()
            }
            for ((key, fileId) in found) if (key !in have && out.size < CACHE_CHECKS) if (key !in out) out[key] = fileId
            if (out.size >= CACHE_CHECKS) break
        }
        return out.map { it.key to it.value }
    }
}
