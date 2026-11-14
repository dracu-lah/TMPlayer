package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.ChoiceGroup
import com.tmplayer.ui.components.GroupedChoice
import com.tmplayer.ui.components.GroupedChoicePopover
import com.tmplayer.ui.components.PopoverAnchor
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/** What [sort] is called in a chat's Sort menu, on every device. */
fun videoSortLabel(sort: VideoSort, s: Messages): String = when (sort) {
    VideoSort.Newest -> s.gridSortNewest
    VideoSort.Oldest -> s.gridSortOldest
    VideoSort.Name -> s.gridSortName
    VideoSort.Year -> s.gridSortYear
    VideoSort.Size -> s.gridSortSize
    VideoSort.Longest -> s.gridSortLongest
    VideoSort.Unwatched -> s.gridSortUnwatched
}

/** The glyph beside [sort] in the menu. */
fun videoSortIcon(sort: VideoSort): ImageVector = when (sort) {
    VideoSort.Newest -> TmIcons.Clock
    VideoSort.Oldest -> TmIcons.History
    VideoSort.Name -> TmIcons.TextSize
    VideoSort.Year -> TmIcons.Trailer
    VideoSort.Size -> TmIcons.Download
    VideoSort.Longest -> TmIcons.Frames
    VideoSort.Unwatched -> TmIcons.Play
}

/** What [filter] is called in the Show group, and in the "Showing ..." line over the grid. */
fun videoFilterLabel(filter: VideoFilter, s: Messages): String = when (filter) {
    VideoFilter.All -> s.gridFilterAll
    VideoFilter.Unwatched -> s.gridFilterUnwatched
    VideoFilter.InProgress -> s.gridFilterInProgress
    VideoFilter.Watched -> s.gridFilterWatched
    VideoFilter.OnDevice -> s.gridFilterOnDevice
    VideoFilter.Shows -> s.gridFilterShows
    VideoFilter.Singles -> s.gridFilterSingles
    VideoFilter.HighDefinition -> s.gridFilterHd
}

/** The glyph beside [filter] in the menu. */
fun videoFilterIcon(filter: VideoFilter): ImageVector = when (filter) {
    VideoFilter.All -> TmIcons.Grid
    VideoFilter.Unwatched -> TmIcons.PlayCircle
    VideoFilter.InProgress -> TmIcons.Pause
    VideoFilter.Watched -> TmIcons.CheckCircle
    VideoFilter.OnDevice -> TmIcons.Folder
    VideoFilter.Shows -> TmIcons.Episodes
    VideoFilter.Singles -> TmIcons.PlaySolid
    VideoFilter.HighDefinition -> TmIcons.AspectRatio
}

/**
 * What a chat's Sort menu holds, the same on a phone, a television and a computer: the order
 * ([VideoSort]) and which videos show ([VideoFilter]). Each pick applies at once. The size
 * limits are not here: they are set in Settings only, and a chat says when they hid something.
 */
class VideoMenuState(
    val sort: VideoSort,
    val filter: VideoFilter,
    val onSort: (VideoSort) -> Unit,
    val onFilter: (VideoFilter) -> Unit,
    /**
     * Plays one of the videos already loaded, picked at random ([com.tmplayer.data.PlayRandom]).
     * Null leaves the line out: an empty list has nothing to pick.
     */
    val onPlayRandom: (() -> Unit)? = null,
)

/** The menu's two groups, as [GroupedChoicePopover] draws them and the television's sheet lists them. */
fun videoMenuGroups(state: VideoMenuState, s: Messages): List<ChoiceGroup> = buildList {
    // First, where the thumb and the remote land: one press away from the Sort button.
    state.onPlayRandom?.let { play ->
        add(ChoiceGroup(s.commonPlay, listOf(GroupedChoice(s.gridPlayRandom, TmIcons.Shuffle, false, play))))
    }
    add(ChoiceGroup(s.gridSortLabel, VideoSort.entries.map { sort -> GroupedChoice(videoSortLabel(sort, s), videoSortIcon(sort), sort == state.sort) { state.onSort(sort) } }))
    add(ChoiceGroup(s.gridFilterLabel, VideoFilter.entries.map { filter -> GroupedChoice(videoFilterLabel(filter, s), videoFilterIcon(filter), filter == state.filter) { state.onFilter(filter) } }))
}

/**
 * A chat's Sort and filter menu on a phone and a computer: one card grown out of the Sort button
 * ([GroupedChoicePopover]) with the order and the Show group. A pick applies and
 * closes the card, so the grid is seen to change under it.
 */
@Composable
fun VideoMenuPopover(open: Boolean, anchor: PopoverAnchor, state: VideoMenuState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    GroupedChoicePopover(open = open, anchor = anchor, groups = videoMenuGroups(state, s), onDismiss = onDismiss)
}
