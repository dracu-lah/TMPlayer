package com.tmplayer.data

import com.tmplayer.data.VideoSorting.Progress

/**
 * Which of a chat's videos its listing shows, from the Show group of its Sort menu, remembered per
 * chat ([SettingsStore.videoFilter]) beside its order ([VideoSort]).
 *
 * Like every order but Newest, a filter works on what has loaded so far: the pages still load as
 * the viewer scrolls, and each one is screened as it arrives.
 */
enum class VideoFilter {
    All,
    Unwatched,
    InProgress,
    Watched,

    /** A whole copy on this device, downloaded or kept in the cache from playing. */
    OnDevice,
    Shows,
    Singles,

    /** A copy of 1080p or 4K, by its name or its stated picture size ([GlobalSearch.qualityOf]). */
    HighDefinition,
    ;

    companion object {
        val DEFAULT = All

        /** A stored name back to a filter; anything unknown is [DEFAULT]. */
        fun decode(name: String?): VideoFilter = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Screens a chat's arranged entries ([SeriesShelf.arrange]) by a [VideoFilter]. Pure and
 * order-keeping, so the grids filter after they sort and the tests cover all three of them.
 *
 * - **Copies.** A film posted several times passes if any of its copies does: one 1080p copy among
 *   720p ones makes it 1080p and up, one copy on the device makes it on this device.
 * - **Shows** go by all their episodes, as the Unwatched order does ([VideoSorting.progressOf]):
 *   Watched only when every episode is, Unwatched only when none is started, In progress between.
 *   The three never overlap, so every entry is under exactly one of them.
 */
object VideoFiltering {

    fun filter(
        entries: List<ShelfEntry>,
        filter: VideoFilter,
        progress: (MediaItem) -> Progress = { Progress.Untouched },
    ): List<ShelfEntry> {
        if (filter == VideoFilter.All) return entries
        return entries.filter { passes(it, filter, progress) }
    }

    fun passes(entry: ShelfEntry, filter: VideoFilter, progress: (MediaItem) -> Progress): Boolean = when (filter) {
        VideoFilter.All -> true
        VideoFilter.Unwatched -> VideoSorting.progressOf(entry, progress) == Progress.Untouched
        VideoFilter.InProgress -> VideoSorting.progressOf(entry, progress) == Progress.InProgress
        VideoFilter.Watched -> VideoSorting.progressOf(entry, progress) == Progress.Watched
        VideoFilter.OnDevice -> VideoSorting.items(entry).any { it.onDevice }
        VideoFilter.Shows -> entry is ShelfEntry.Show
        VideoFilter.Singles -> entry is ShelfEntry.File
        VideoFilter.HighDefinition -> VideoSorting.items(entry).any { copy ->
            (GlobalSearch.qualityOf(copy)?.let(GlobalSearch::qualityRank) ?: 0) >= HD_RANK
        }
    }

    /** [GlobalSearch.qualityRank] of 1080p. */
    private const val HD_RANK = 3
}
