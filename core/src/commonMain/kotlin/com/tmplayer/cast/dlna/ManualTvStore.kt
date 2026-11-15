package com.tmplayer.cast.dlna

import com.tmplayer.platform.Logger
import java.io.File

/**
 * The TVs the person added by their address, remembered across runs in one small text file (a line
 * each: description link, UDN, name, tab separated). Kept on this device only, not in a backup: an
 * address means nothing on another network.
 *
 * Whatever is in the file is not trusted: a line that is not a link [ManualAddress] accepts is
 * skipped, a cut or odd file gives fewer rows and never an error, and at most [MAX] are kept. A TV
 * added again (same UDN, or the same link) replaces its line, which is how a TV that changed its
 * address is updated. Written to a temporary file and renamed, so a crash leaves the old list.
 */
class ManualTvStore(
    private val file: File,
    private val allowLoopback: Boolean = false,
) {
    data class Saved(val location: String, val udn: String, val name: String) {
        /** `192.168.1.20` or `192.168.1.20:49152`, for the list in the dialog. */
        val address: String get() = (ManualAddress.savedTarget(location, allowLoopback = true))?.display ?: location
    }

    @Synchronized
    fun load(): List<Saved> {
        val text = runCatching { if (file.isFile && file.length() <= MAX_FILE) file.readText(Charsets.UTF_8) else null }.getOrNull() ?: return emptyList()
        return parse(text, allowLoopback)
    }

    /** The links, for [DlnaDiscovery]'s `manual`. */
    fun locations(): List<String> = load().map { it.location }

    @Synchronized
    fun add(location: String, udn: String, name: String) {
        if (ManualAddress.savedTarget(location, allowLoopback) == null) return
        val entry = Saved(location.trim(), clean(udn), clean(name))
        val rows = load().filterNot { it.location == entry.location || (entry.udn.isNotEmpty() && it.udn == entry.udn) } + entry
        write(rows.takeLast(MAX))
    }

    @Synchronized
    fun remove(location: String) {
        val rows = load()
        val kept = rows.filterNot { it.location == location }
        if (kept.size != rows.size) write(kept)
    }

    private fun write(rows: List<Saved>) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(rows.joinToString("\n") { "${it.location}\t${it.udn}\t${it.name}" } + "\n", Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "The TV list was not saved", e)
        }
    }

    companion object {
        private const val TAG = "ManualTvStore"
        const val MAX = 32
        private const val MAX_FILE = 64 * 1024L

        private fun clean(text: String): String = text.filter { it.code >= 0x20 && it != '\u007F' }.trim().take(120)

        /** The rows of a saved file's [text]; pure, for the tests. */
        fun parse(text: String, allowLoopback: Boolean = false): List<Saved> {
            val rows = LinkedHashMap<String, Saved>()
            for (line in text.lineSequence().take(MAX * 4)) {
                val parts = line.split('\t')
                val location = parts.getOrNull(0)?.trim().orEmpty()
                if (ManualAddress.savedTarget(location, allowLoopback) == null) continue
                rows[location] = Saved(location, clean(parts.getOrNull(1).orEmpty()), clean(parts.getOrNull(2).orEmpty()))
                if (rows.size >= MAX) break
            }
            return rows.values.toList()
        }
    }
}
