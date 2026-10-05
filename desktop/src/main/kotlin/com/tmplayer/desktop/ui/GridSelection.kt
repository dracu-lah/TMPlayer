package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The posters picked in a chat's grid for "Download selected": Ctrl+click (Cmd+click on macOS)
 * adds or takes away one, Shift+click takes in everything from the last one picked.
 *
 * Indices are the grid's, ids are [MediaItem.id]; the ids are what is kept, so a page loading
 * underneath does not change what was picked.
 */
@Stable
class GridSelection(private val idAt: (Int) -> String?) {
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set
    private var anchor: Int? = null

    val active: Boolean get() = ids.isNotEmpty()

    fun isSelected(id: String) = id in ids

    /** Ctrl+click: one poster in or out. */
    fun toggle(index: Int) {
        val id = idAt(index) ?: return
        ids = if (id in ids) ids - id else ids + id
        anchor = index
    }

    /** Shift+click: everything between the last poster picked and this one, added. */
    fun extend(index: Int) {
        val from = anchor ?: return toggle(index)
        val range = if (from <= index) from..index else index..from
        ids = ids + range.mapNotNull(idAt)
        anchor = index
    }

    fun selectAll(all: List<String>) {
        ids = all.toSet()
    }

    fun clear() {
        ids = emptySet()
        anchor = null
    }
}

/** The grid's selection, for the posters inside it; null where nothing can be picked. */
internal val LocalGridSelection = compositionLocalOf<GridSelection?> { null }

/**
 * The downloads index by [MediaItem.id], read once per grid rather than once per poster, so every
 * badge and menu says "Downloaded" from the same answer.
 */
internal val LocalDownloadIndex = staticCompositionLocalOf<Map<String, ResumeRecord>> { emptyMap() }

/** The bar over the grid while posters are picked: how many, Select all, Download selected, Clear. */
@Composable
internal fun SelectionBar(
    state: ShellState,
    selection: GridSelection,
    items: List<MediaItem>,
    chatTitle: String,
    index: Map<String, ResumeRecord>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    Surface(shape = MaterialTheme.shapes.large, color = Tone.surfaceHigh, tonalElevation = 6.dp, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val count = selection.ids.size
            Text(if (count == 1) "1 selected" else "$count selected", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { selection.selectAll(items.filter { it.canBeSaved }.map { it.id }) }) { Text("Select all") }
            TextButton(onClick = { selection.clear() }) { Text("Clear") }
            Button(onClick = {
                val chosen = items.filter { selection.isSelected(it.id) }
                selection.clear()
                scope.launch {
                    val title = chatTitle.ifBlank { chosen.firstOrNull()?.let { state.chatTitleOf(it.chatId) }.orEmpty() }
                    toast(downloadSelected(state.downloads, state.settings, chosen, title, index))
                }
            }) { Text("Download selected") }
        }
    }
}

/**
 * Queues every picked video that will fit, over [CacheShelf.planBatch]: one already downloaded or
 * coming down is skipped, one whole in the cache is moved rather than fetched and costs nothing,
 * and the rest are planned against the cache drive's free space with the cached videos nobody
 * picked offered as room. Returns the sentence for the toast.
 */
internal suspend fun downloadSelected(
    runner: DownloadRunner,
    settings: SettingsStore,
    chosen: List<MediaItem>,
    chatTitle: String,
    index: Map<String, ResumeRecord>,
): String {
    val active = OfflineDownloads.active.value
    val wanted = chosen.filter { item -> item.canBeSaved && item.id !in index && active[item.fileId]?.busy != true }
    if (wanted.isEmpty()) return "Those are in Downloads already"
    val candidates = wanted.map { item ->
        CacheShelf.Candidate(
            fileId = item.fileId,
            sizeBytes = item.sizeBytes,
            partialBytes = runCatching { Td.localDownloadedBytes(item.fileId) }.getOrDefault(0L),
            alreadyHere = item.onDevice,
        )
    }
    val records = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
    val held = records.map { CacheShelf.Held(it.fileId, runCatching { Td.localDownloadedBytes(it.fileId) }.getOrDefault(0L), it.updatedAt) }
    val batch = CacheShelf.planBatch(candidates, held, DesktopPaths.disk().freeBytes)
    for (id in batch.reclaimFileIds) {
        runCatching { Td.deleteFile(id) }
        records.firstOrNull { it.fileId == id }?.let { runCatching { settings.forgetCachedVideo(it.chatId, it.messageId) } }
    }
    val starting = (batch.alreadyHere + batch.fits).sorted().map { wanted[it] }
    starting.forEach { OfflineDownloads.start(runner, it, chatTitle) }
    val left = wanted.size - starting.size
    val queued = if (starting.size == 1) "Downloading 1 video" else "Downloading ${starting.size} videos"
    return when {
        starting.isEmpty() -> "Not enough space for any of them"
        left > 0 -> "$queued. $left did not fit"
        else -> queued
    }
}
