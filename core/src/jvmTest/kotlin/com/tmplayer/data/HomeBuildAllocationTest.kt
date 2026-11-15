package com.tmplayer.data

import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What building Home costs in garbage, measured rather than guessed: a 1 GB television stick
 * spent the first twelve seconds of a cold start collecting garbage, roughly once a second, while
 * Home was built over and over as each chat's videos arrived.
 *
 * The input is a cold start's: ten chats of two hundred release-named videos each (films, shows
 * by the season, Tamil and Malayalam channel uploads with their tags, camera files with captions),
 * first drawn from the cache, then each chat's answer from Telegram landing in turn as fresh items,
 * with Home built again after every one. The test prints the bytes allocated and the time taken,
 * and holds the allocation under a ceiling, so a change that puts per-build parsing back shows up
 * here rather than on the stick.
 */
class HomeBuildAllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Cost(val bytes: Long, val nanos: Long) {
        override fun toString() = "${bytes / 1024} KB, ${nanos / 1_000_000} ms"
    }

    private inline fun measure(block: () -> Unit): Cost {
        val bytes = threads.currentThreadAllocatedBytes
        val start = System.nanoTime()
        block()
        return Cost(threads.currentThreadAllocatedBytes - bytes, System.nanoTime() - start)
    }

    /** The cheapest of a few runs: the first ones pay for class loading and the JIT. */
    private inline fun best(runs: Int = 5, block: () -> Unit): Cost {
        var bestBytes = Long.MAX_VALUE
        var bestNanos = Long.MAX_VALUE
        repeat(runs) {
            val cost = measure(block)
            bestBytes = minOf(bestBytes, cost.bytes)
            bestNanos = minOf(bestNanos, cost.nanos)
        }
        return Cost(bestBytes, bestNanos)
    }

    @Test
    fun `a cold start's home builds stay cheap`() {
        val chats = (1L..CHATS).map { ChatSummary(it, "Chat $it", null, 0, ChatKind.Channel) }
        val cached = chats.associate { it.id to videos(it.id) }
        val recent = cached.values.flatten().sortedByDescending { it.date }.take(100)
        val resume = cached.values.flatten().filterIndexed { index, _ -> index % 97 == 0 }.take(12).mapIndexed { index, item ->
            ResumeRecord(
                chatId = item.chatId, messageId = item.messageId, fileId = item.fileId, title = item.title,
                chatTitle = "", sizeBytes = item.sizeBytes, durationSec = item.durationSec, positionMs = 60_000,
                durationMs = item.durationSec * 1000L, updatedAt = 1_000L + index, fileName = item.fileName,
                caption = item.caption,
            )
        }

        fun build(loaded: Map<Long, List<MediaItem>>, recent: List<MediaItem>?) = HomeRows.build(
            resume, HomeRows.homeChats(chats, emptySet(), recent), loaded, recent,
            recentMinStarred = 1, starred = emptySet(), autoFill = true,
        )

        // Every item new, nothing read off it yet: Home from the cache on a cold start.
        val cold = best { build(fresh(cached), fresh(recent)) }
        // The same items again: a build that only reshuffles what is already read.
        val warmLoaded = fresh(cached)
        val warmRecent = fresh(recent)
        build(warmLoaded, warmRecent)
        val warm = best { build(warmLoaded, warmRecent) }
        // The cold start as it happens: the cache, then each chat's fresh answer in turn.
        val sequence = best(3) {
            var loaded = fresh(cached)
            val across = fresh(recent)
            build(loaded, across)
            for (chat in chats) {
                loaded = loaded + (chat.id to fresh(cached.getValue(chat.id)))
                build(loaded, across)
            }
        }
        val names = cached.values.flatten().map { it.fileName }
        val parse = best { names.forEach { MediaName.parse(it) } }
        val text = HomeCache.encode(HomeCache.Snapshot(cached, recent, emptySet(), emptyMap(), 0L))
        val decode = best { HomeCache.decode(text) }
        val rows = build(warmLoaded, warmRecent)
        println("home build, cold: $cold")
        println("home build, warm: $warm")
        println("home cold start, cache then ${chats.size} answers: $sequence")
        println("MediaName.parse x${names.size}: $parse")
        println("HomeCache.decode (${text.length / 1024} KB): $decode")
        println("rows: " + rows.joinToString(" | ") { row -> "${row.key}:" + entriesOf(row).joinToString(",") { it } })
        assertTrue(rows.isNotEmpty())
        assertEquals("the same rows from a cold build", rows, build(fresh(cached), fresh(recent)))
        // Well under what the same start allocated before 2026-10-23, with room for noise.
        assertTrue("cold start allocated $sequence", sequence.bytes < SEQUENCE_CEILING)
    }

    private fun entriesOf(row: HomeRow): List<String> = when (row) {
        is HomeRow.Continue -> row.records.map { "${it.chatId}:${it.messageId}" }
        is HomeRow.WatchLater -> row.records.map { it.key }
        is HomeRow.Listed -> row.records.map { it.key }
        is HomeRow.NewEpisodes -> row.episodes.map { it.item.id }
        is HomeRow.Chat -> row.entries.map { it.key }
        is HomeRow.Recent -> row.entries.map { it.key }
    }

    /** New instances of the same videos, nothing cached on them, as Telegram's mapper hands them over. */
    private fun fresh(items: List<MediaItem>): List<MediaItem> = items.map { it.copy() }

    private fun fresh(loaded: Map<Long, List<MediaItem>>): Map<Long, List<MediaItem>> = loaded.mapValues { fresh(it.value) }

    private fun videos(chatId: Long): List<MediaItem> = (0 until PER_CHAT).map { n ->
        val messageId = (chatId * 10_000 + n) shl 20
        val (fileName, caption) = name(chatId.toInt(), n)
        MediaItem(
            chatId = chatId,
            messageId = messageId,
            fileId = (chatId * 10_000 + n).toInt(),
            title = fileName.ifBlank { caption.lineSequence().first() },
            sizeBytes = 300_000_000L + (n * 7_919_000L) % 2_000_000_000L,
            durationSec = if (n % 5 == 0) 0 else 1_500 + (n * 37) % 6_000,
            mimeType = "video/x-matroska",
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = 1_760_000_000 + (chatId * 1_000 + n * 600).toInt(),
            fileName = fileName,
            caption = caption,
        )
    }

    /** A release name and caption of the kinds the channels post, varied by chat and position. */
    private fun name(chat: Int, n: Int): Pair<String, String> {
        val film = FILMS[(n + chat * 3) % FILMS.size]
        val year = 1995 + (n * 7 + chat) % 30
        val show = SHOWS[(n / 7 + chat) % SHOWS.size]
        val season = 1 + (n / 40) % 3
        val episode = 1 + n % 12
        val res = listOf("480p", "720p", "1080p", "2160p")[(n + chat) % 4]
        val tag = listOf("TG@MalluMovies", "TamilBlasters", "1TamilMV", "SubsPlease")[chat % 4]
        return when (n % 10) {
            0 -> "${film.replace(' ', '.')}.$year.$res.WEB-DL.x264-GRP.mkv" to ""
            1 -> "[$tag] $film ($year) Malayalam HQ HDRip - $res - x264 - (DD+5.1 - 192Kbps & AAC) - 2.5GB - ESub.mkv" to ""
            2 -> "www.1TamilMV.org - $film ($year) Tamil TRUE WEB-DL - $res - AVC - (AAC 2.0) - 1.4GB.mkv" to "$film ($year)\nJoin @channel"
            3 -> "${show.replace(' ', '.')}.S0${season}E${episode.toString().padStart(2, '0')}.$res.NF.WEB-DL.DDP5.1.x264.mkv" to ""
            4 -> "$show S0${season}E${episode.toString().padStart(2, '0')} $res" to "$show\nEpisode $episode"
            5 -> "@HEVC_Mob_${show.replace(' ', '_')}_EP${episode}_$res.mkv" to ""
            6 -> "VID_2025${(n % 12 + 1).toString().padStart(2, '0')}12_1234${n % 10}.mp4" to "$film ($year) $res\nUploaded by @channel"
            7 -> "[$tag] ${show} - ${episode.toString().padStart(2, '0')} ($res) [ABCD1234].mkv" to ""
            8 -> "${film.lowercase().replace(' ', '_')}_${res}.mp4" to "$film\nPart of the collection"
            else -> "$film ${year} $res Hindi Dubbed Dual Audio.mkv" to "$film ($year) Hindi"
        }
    }

    private companion object {
        const val CHATS = 10L
        const val PER_CHAT = 200

        /**
         * The cold start measured about 81 MB after the caching of 2026-10-23 and 542 MB before it:
         * a build that reads names afresh again lands well over this.
         */
        const val SEQUENCE_CEILING = 160L * 1024 * 1024

        val FILMS = listOf(
            "Aavesham", "Kishkindha Kaandam", "Manjummel Boys", "Premalu", "Bramayugam", "Leo", "Jailer",
            "Vikram", "Night Train", "Harbour Lights", "The Last Garden", "Golden Hour", "Paper Boats",
            "Kaathal The Core", "Romancham", "Thallumaala", "Minnal Murali", "Kumbalangi Nights",
            "Big Buck Bunny", "Sintel", "Tears of Steel", "Elephants Dream", "Cosmos Laundromat",
        )
        val SHOWS = listOf(
            "Kerala Crime Files", "Harbour Notes", "Studio Log", "Garden Notes", "Family Full House",
            "Night Shift", "The Lighthouse Keepers", "Pond Life", "Kitchen Tales",
        )
    }
}
