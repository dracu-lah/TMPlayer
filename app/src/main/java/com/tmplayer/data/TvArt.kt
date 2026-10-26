package com.tmplayer.data

import android.content.Context
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata
import java.io.File
import java.security.MessageDigest

/**
 * The pictures the TV home screen shows, made readable for the launcher: both the channels'
 * cards ([TvChannelsPublisher]) and the "Play next" row ([WatchNextPublisher]) hand the launcher a
 * `content://` URI of [TvArtProvider], backed by a copy of the picture in [TvArtProvider.dir].
 *
 * A copy's name starts with the kind that owns it, so each publisher only prunes its own: the
 * channels' copies ([CHANNEL_KINDS]) and "Play next"'s ([PLAY_NEXT]). One publisher clearing out
 * the pictures the other still points at left that row with an empty frame.
 */
internal object TvArt {

    /** Prefix of the copies "Play next" entries use, whether wide online or Telegram's thumbnail. */
    const val PLAY_NEXT = "n"

    /** Prefixes of the copies the channels' cards use: wide ("w") and the promo build's fixtures ("f"). */
    val CHANNEL_KINDS = setOf("w", "f", "p")

    /**
     * Where a thumbnail file's path comes from: TDLib, downloading it first when it is not on the
     * device yet. A seam, so a test can say the thumbnail is missing, late, or here.
     */
    @Volatile
    internal var thumbnailPath: suspend (Int) -> String? = { fileId -> Thumbnails.path(fileId) }

    /** Whether wide online pictures are looked for at all; a test turns it off so it never reaches the network. */
    @Volatile
    internal var onlinePictures: Boolean = true

    /**
     * What one publish may still ask of the network for pictures. A card the run cannot afford is
     * left out and asked for again on the next run.
     */
    class OnlineBudget(var left: Int) {
        fun take(): Boolean {
            if (left <= 0) return false
            left--
            return true
        }
    }

    /**
     * The film's or show's wide picture (its backdrop, an episode's still) copied in under
     * [prefix], or null when the online lookups are off, do not know the title, or the run's
     * [budget] could not pay for the lookup or the download.
     */
    suspend fun wide(context: Context, art: TvChannels.Art, budget: OnlineBudget, prefix: String): String? {
        if (!onlinePictures) return null
        val info = found(art, budget) ?: return null
        val url = info.wideUrl ?: return null
        if (!budget.take()) return null
        return fetched(context, url, prefix)
    }

    /**
     * Telegram's own thumbnail of the video, downloaded if it is not on the device, copied in under
     * [prefix] and named after [key] (the video, not the file id, which a new session may renumber).
     */
    suspend fun thumbnail(context: Context, fileId: Int, key: String, prefix: String): String? {
        if (fileId <= 0) return null
        val path = runCatching { thumbnailPath(fileId) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return copyIn(context, File(path), "${prefix}_${sha1(key)}.jpg")
    }

    /** What the online lookups know of the film or show, when the viewer turned them on. */
    private suspend fun found(art: TvChannels.Art, budget: OnlineBudget): com.tmplayer.online.MetaInfo? {
        val online = OnlineMetadata.current ?: return null
        if (!OnlineMetadata.enabled) return null
        val query = MetaQuery.of(art.name, art.caption.ifBlank { null })?.showOnly() ?: return null
        // What Home has already learnt costs nothing; only a title not yet looked up spends the run's budget.
        online.peek(query)?.let { known -> return (known as? MetaResult.Found)?.info }
        runCatching { online.cachedMatch(query) }.getOrNull()?.let { return it }
        if (!budget.take()) return null
        return (runCatching { online.lookup(query) }.getOrNull() as? MetaResult.Found)?.info
    }

    /** One online picture, fetched and copied in under [prefix]. */
    private suspend fun fetched(context: Context, url: String, prefix: String): String? {
        val online = OnlineMetadata.current ?: return null
        val file = runCatching { online.image(url) }.getOrNull() ?: return null
        return copyIn(context, file, "${prefix}_${sha1(url)}.jpg")
    }

    /** Puts [source] into [TvArtProvider.dir] as [name]; the name, or null when it could not be. */
    fun copyIn(context: Context, source: File, name: String): String? = runCatching {
        if (!TvArtProvider.isValidName(name) || !source.isFile || source.length() == 0L) return null
        val dir = TvArtProvider.dir(context).apply { mkdirs() }
        val target = File(dir, name)
        if (!target.isFile || target.length() != source.length()) {
            val temp = File(dir, "$name.part")
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
        }
        name
    }.getOrNull()

    /** The copy a stored picture URI names, when it is one of this app's. */
    fun artName(context: Context, uri: String?): String? {
        val prefix = "content://${TvArtProvider.authority(context)}/"
        if (uri == null || !uri.startsWith(prefix)) return null
        return uri.removePrefix(prefix).takeIf { TvArtProvider.isValidName(it) }
    }

    /**
     * Whether a row's stored picture is one of this app's copies that is no longer on disk (the
     * viewer cleared the app's storage, or an older build pruned it): the launcher can no longer
     * open it, so the row is written again. Anything else, the app's mark included, is not
     * "missing" here.
     */
    fun copyMissing(context: Context, uri: String?): Boolean {
        val name = artName(context, uri) ?: return false
        return !File(TvArtProvider.dir(context), name).isFile
    }

    /** Deletes the copies of the [kinds] that no row names ([kept]) any more. */
    fun prune(context: Context, kinds: Set<String>, kept: Set<String>) {
        runCatching {
            TvArtProvider.dir(context).listFiles()?.forEach { file ->
                val kind = file.name.substringBefore('_', "")
                // A half copied file left by a crash goes too; anything of another kind is not ours.
                if (kind in kinds && (file.name.endsWith(".part") || file.name !in kept)) file.delete()
            }
        }
    }

    fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
