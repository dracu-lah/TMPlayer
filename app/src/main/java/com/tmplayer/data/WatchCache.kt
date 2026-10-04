package com.tmplayer.data

import android.content.Context

/**
 * The videos playing left behind, on Android: [WatchCacheRules] with the one video rule, over
 * TDLib's cache directory as [AndroidPaths] has it, and the set of files a player has open.
 *
 * The rules themselves (who claims the disk, who gives it up, and what to do about the copies
 * nothing has a record of) are shared with the desktop and live in `:core`. What is Android's is
 * the context every caller already has, and [startedPlaying]/[stoppedPlaying], which the player
 * activity calls as it opens and closes a file.
 *
 * [claim] is called from the player rather than from any screen, so it runs once for every video
 * that is ever opened, however it was opened: stepping to the next episode starts the player
 * directly and never passes a browse screen.
 */
object WatchCache {

    /**
     * The one set of rules for the process, built on first use.
     *
     * One instance, because the claim lock and the claims in flight have to be shared by every
     * caller to mean anything. The phone and the TV keep one cached video ([CacheRule.OneVideo]),
     * and eviction does not look at [playing]: it never has here, and the claim of the video now
     * playing is what spares it.
     */
    @Volatile
    private var rules: WatchCacheRules? = null

    private fun rules(context: Context): WatchCacheRules = rules ?: synchronized(this) {
        rules ?: context.applicationContext.let { app ->
            WatchCacheRules(
                settings = SettingsStore(app),
                filesRoot = { AndroidPaths.cacheDir(app) },
                playing = ::playingNow,
                rule = { CacheRule.OneVideo },
                sparePlaying = false,
            )
        }.also { rules = it }
    }

    /**
     * Marks a video as the one the cache is holding, and gives up the ones it was holding before.
     *
     * Called at the top of every playback. Idempotent: opening the same video twice claims it
     * twice and deletes nothing. Three kinds of file survive it: the video being played, anything
     * the viewer downloaded on purpose, and anything a download is currently fetching.
     */
    suspend fun claim(context: Context, item: MediaItem, chatTitle: String) {
        rules(context).claim(item, chatTitle)
    }

    /**
     * The files open in a player right now, counted, because backing out of a video and opening it
     * again can briefly put two players on one file.
     *
     * The player's claim on the cache is written in the background, after its download has begun,
     * so for the first moments of a watch the file has no record at all. A sweep running then, at
     * launch or on the way out of the previous episode, took that for a stray and deleted the part
     * file underneath the download: the player sat on Loading until it was closed and opened again.
     */
    private val playing = mutableMapOf<Int, Int>()

    fun startedPlaying(fileId: Int) {
        if (fileId <= 0) return
        synchronized(playing) { playing[fileId] = (playing[fileId] ?: 0) + 1 }
    }

    fun stoppedPlaying(fileId: Int) {
        if (fileId <= 0) return
        synchronized(playing) {
            val left = (playing[fileId] ?: 0) - 1
            if (left > 0) playing[fileId] = left else playing.remove(fileId)
        }
    }

    fun isPlaying(fileId: Int): Boolean = synchronized(playing) { fileId in playing }

    private fun playingNow(): Set<Int> = synchronized(playing) { playing.keys.toSet() }

    /** Deletes every cached video except [keepFileId]. See [WatchCacheRules.evictAllBut]. */
    suspend fun evictAllBut(context: Context, keepFileId: Int) {
        rules(context).evictAllBut(keepFileId)
    }

    /**
     * Every video file on this device that is neither a download nor a cached video we can name.
     *
     * Found by walking TDLib's own files directory rather than by asking TDLib, because TDLib
     * reports the total and the breakdown but not the individual files, and a total on its own is
     * something the viewer can do nothing with.
     *
     * @param knownPaths the local paths of everything already on the Downloads screen, which are
     *   asked of TDLib by the caller: only it knows which records are worth resolving.
     */
    fun strays(context: Context, knownPaths: Set<String>): List<WatchCacheRules.Stray> =
        rules(context).strays(knownPaths)

    /** The same, against a directory rather than a device, so the rule can be tested. */
    internal fun straysIn(root: java.io.File, knownPaths: Set<String>): List<WatchCacheRules.Stray> =
        WatchCacheRules.straysIn(root, knownPaths)

    /** Removes one stray from the disk. TDLib refetches it if the video is ever played again. */
    fun forget(context: Context, stray: WatchCacheRules.Stray): Boolean = rules(context).forget(stray)

    /**
     * Empties the cache: every video that is not a download, and every stray beside them.
     *
     * The one implementation of "clear the cached videos", used by both Settings rows that mean it.
     *
     * @return how many bytes went.
     */
    suspend fun clearAll(context: Context): Long = rules(context).clearAll()

    /**
     * Takes the videos nothing owns, without being asked.
     *
     * Runs where the ceiling trim runs, on launch and on the way out of a video, so a device does
     * not need the viewer to know about Settings and press Clear to get its space back.
     *
     * @return how many bytes went, which is worth logging and nothing else.
     */
    suspend fun sweep(context: Context): Long = rules(context).sweep()
}
