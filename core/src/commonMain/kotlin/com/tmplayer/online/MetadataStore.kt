package com.tmplayer.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * The poster and overview switches as this device knows them.
 *
 * [enabled] is off until the viewer turns it on: a lookup sends a video's cleaned name to TMDB,
 * TVmaze or AniList, which nobody should get without asking. [ownKey] is a TMDB key of the
 * viewer's own, used instead of the build's. [refusedKey] is a fingerprint of a key TMDB refused,
 * so TMDB stays off for that key and comes back by itself when an update brings another.
 */
data class MetaSettings(
    val enabled: Boolean = false,
    val ownKey: String = "",
    val refusedKey: String = "",
    val refusedAt: Long = 0,
)

/** [MetaSettings] kept in a small properties file in the app's private storage. */
class MetadataStore(private val file: File) {

    private val state = MutableStateFlow(load())

    val settings: StateFlow<MetaSettings> = state.asStateFlow()

    val now: MetaSettings get() = state.value

    @Synchronized
    fun update(change: (MetaSettings) -> MetaSettings) {
        val next = change(state.value)
        if (next == state.value) return
        save(next)
        state.value = next
    }

    private fun load(): MetaSettings {
        val p = Properties()
        runCatching { if (file.isFile) file.inputStream().use { p.load(it) } }
        return MetaSettings(
            enabled = p.getProperty("enabled") == "true",
            ownKey = p.getProperty("own_key").orEmpty(),
            refusedKey = p.getProperty("refused_key").orEmpty(),
            refusedAt = p.getProperty("refused_at")?.toLongOrNull() ?: 0L,
        )
    }

    private fun save(s: MetaSettings) {
        val p = Properties()
        p["enabled"] = s.enabled.toString()
        p["own_key"] = s.ownKey
        p["refused_key"] = s.refusedKey
        p["refused_at"] = s.refusedAt.toString()
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { p.store(it, null) }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
