package com.tmplayer.player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The subtitle file TMPlayer hands to VLC or MX Player with a video ([ExternalExtras]): the one the
 * viewer last loaded or had fetched for this video, else one that sits beside a downloaded file
 * with the same name (`Film.mkv` and `Film.srt` or `Film.en.srt`). Pure file logic, so the rules
 * (a file that is gone, empty or huge is skipped) are tested without a device.
 */
object ExternalSubtitleFile {

    /**
     * Whether [file] can go to another app as a subtitle: it is there, not empty, no larger than
     * [ExternalSubtitle.MAX_BYTES] and has a name the players read (`.sub` is left out: VobSub
     * needs its `.idx`, and MicroDVD was already turned into SubRip when it was loaded).
     */
    fun usable(file: File?): Boolean {
        file ?: return false
        if (ExternalSubtitle.extensionOf(file.name) !in HANDED_OVER) return false
        val length = runCatching { if (file.isFile) file.length() else -1L }.getOrDefault(-1L)
        return length in 1..ExternalSubtitle.MAX_BYTES
    }

    /** The files beside [video] named as it is (same name up to the extension, or with a language tag in between), best first. */
    fun sidecarsOf(video: File, preferredLanguage: String? = null): List<File> {
        val folder = video.parentFile ?: return emptyList()
        val stem = video.nameWithoutExtension
        if (stem.isEmpty()) return emptyList()
        val names = runCatching { folder.listFiles() }.getOrNull() ?: return emptyList()
        val language = preferredLanguage?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return names.asSequence()
            .filter { it.isFile && it.name.startsWith("$stem.") && ExternalSubtitle.extensionOf(it.name) in HANDED_OVER }
            .filter { it.name.removePrefix("$stem.").count { c -> c == '.' } <= 1 }
            .sortedWith(
                compareByDescending<File> { language != null && ExternalSubtitle.languageOf(it.name)?.startsWith(language) == true }
                    .thenBy { ExternalSubtitle.languageOf(it.name) != null }
                    .thenBy { it.name },
            )
            .take(MAX_SIDECARS)
            .toList()
    }

    /** What to hand over: the remembered file when it is usable, else the first usable sidecar, else null. */
    fun pick(remembered: File?, sidecars: List<File>): File? =
        remembered?.takeIf { usable(it) } ?: sidecars.firstOrNull { usable(it) }

    /**
     * The address the other app reads [file] at, or null when the app cannot hand it out. The
     * file is under the cache (a loaded or fetched subtitle) or TMPlayer's own Downloads folder, both of which the
     * FileProvider serves; anything else is not offered.
     */
    fun handoutUri(context: Context, file: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    }.getOrNull()

    /** The subtitle for [video] as a handout address, from the memory and the video's neighbours, or null. */
    fun forVideo(context: Context, key: String, video: File?, preferredLanguage: String? = null): Uri? {
        val remembered = SubtitleMemory.at(context).recall(key)?.let(::File)
        val file = pick(remembered, video?.let { sidecarsOf(it, preferredLanguage) }.orEmpty()) ?: return null
        return handoutUri(context, file)
    }

    private val HANDED_OVER = setOf("srt", "ass", "ssa", "vtt")
    private const val MAX_SIDECARS = 20
}

/**
 * Which subtitle file was last put over which video, so the video can be handed to another player
 * with it (and so a subtitle fetched for a video is not fetched again). A small bounded file in
 * the app's cache: the files it names are cache too, so both go together, and a name whose file is
 * gone is skipped by [ExternalSubtitleFile.usable]. Keys are `chatId:messageId`, never a title.
 * Garbage or a missing file reads as empty; a write that fails is dropped.
 */
class SubtitleMemory(private val file: File, private val max: Int = MAX_ENTRIES) {

    private var loaded: LinkedHashMap<String, String>? = null

    @Synchronized
    fun recall(key: String): String? = entries()[key]

    @Synchronized
    fun remember(key: String, path: String) {
        if (key.isBlank() || path.isBlank() || key.length > MAX_TEXT || path.length > MAX_TEXT) return
        val map = entries()
        map.remove(key)
        map[key] = path
        while (map.size > max) map.remove(map.keys.first())
        write(map)
    }

    @Synchronized
    fun forget(key: String) {
        val map = entries()
        if (map.remove(key) != null) write(map)
    }

    private fun write(map: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in map) json.put(k, v)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun entries(): LinkedHashMap<String, String> {
        loaded?.let { return it }
        val map = LinkedHashMap<String, String>()
        runCatching {
            if (file.isFile && file.length() in 1..MAX_FILE) {
                val json = JSONObject(file.readText())
                for (key in json.keys()) {
                    if (map.size >= max) break
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank() && key.length <= MAX_TEXT && value.length <= MAX_TEXT) map[key] = value
                }
            }
        }
        loaded = map
        return map
    }

    companion object {
        const val MAX_ENTRIES = 100
        private const val MAX_TEXT = 512
        private const val MAX_FILE = 256L * 1024

        @Volatile
        private var shared: SubtitleMemory? = null

        fun at(context: Context): SubtitleMemory =
            shared ?: synchronized(this) {
                shared ?: SubtitleMemory(File(context.applicationContext.cacheDir, "subtitle-memory.json")).also { shared = it }
            }

        /** The key a video is remembered under. */
        fun keyOf(chatId: Long, messageId: Long): String = "$chatId:$messageId"
    }
}
