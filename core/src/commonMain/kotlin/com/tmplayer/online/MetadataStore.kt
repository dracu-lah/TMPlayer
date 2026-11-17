package com.tmplayer.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/** [MetaSettings] kept in a small properties file in the app's private storage. */
class MetadataStore(private val file: File) : MetaSettingsStore {

    private val state = MutableStateFlow(load())

    override val settings: StateFlow<MetaSettings> = state.asStateFlow()

    override val now: MetaSettings get() = state.value

    @Synchronized
    override fun update(change: (MetaSettings) -> MetaSettings) {
        val next = change(state.value)
        if (next == state.value) return
        save(next)
        state.value = next
    }

    private fun load(): MetaSettings {
        val p = Properties()
        runCatching { if (file.isFile) file.inputStream().use { p.load(it) } }
        // Posters were turned back on once for everybody (user, 2026-10-22); what is chosen after
        // that, with the mark saved beside it, stands.
        val onAgain = p.getProperty(ON_AGAIN) == "true"
        return MetaSettings(
            enabled = !onAgain || p.getProperty("enabled") != "false",
            ownKey = p.getProperty("own_key").orEmpty(),
            refusedKey = p.getProperty("refused_key").orEmpty(),
            refusedAt = p.getProperty("refused_at")?.toLongOrNull() ?: 0L,
            omdbKey = p.getProperty("omdb_key").orEmpty(),
            omdbRefusedKey = p.getProperty("omdb_refused_key").orEmpty(),
            skipTimes = p.getProperty("skip_times") != "false",
            trailersInApp = p.getProperty("trailers_in_app") != "false",
            skipModes = SkipModes.decode(p.getProperty("skip_modes")),
            offByChoice = onAgain && p.getProperty("off_by_choice") == "true",
        )
    }

    private fun save(s: MetaSettings) {
        val p = Properties()
        p["enabled"] = s.enabled.toString()
        p["own_key"] = s.ownKey
        p["refused_key"] = s.refusedKey
        p["refused_at"] = s.refusedAt.toString()
        p["omdb_key"] = s.omdbKey
        p["omdb_refused_key"] = s.omdbRefusedKey
        p["skip_times"] = s.skipTimes.toString()
        p["trailers_in_app"] = s.trailersInApp.toString()
        p["skip_modes"] = s.skipModes.encode()
        p["off_by_choice"] = s.offByChoice.toString()
        p[ON_AGAIN] = "true"
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

    private companion object {
        /** Saved with every write once posters were turned back on for everybody; see [load]. */
        const val ON_AGAIN = "posters_on_again"
    }
}
