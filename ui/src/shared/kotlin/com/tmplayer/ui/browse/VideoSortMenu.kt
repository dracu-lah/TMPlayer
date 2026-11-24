package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.VideoFacets
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
    VideoSort.LastPlayed -> s.gridSortLastPlayed
    VideoSort.Unwatched -> s.gridSortUnwatched
}

/**
 * The glyph beside [sort] in the menu: one of its own in the group (Oldest and Last played once
 * shared one), and Unwatched first wears the Unwatched filter's glyph, as the same idea.
 */
fun videoSortIcon(sort: VideoSort): ImageVector = when (sort) {
    VideoSort.Newest -> TmIcons.Clock
    VideoSort.Oldest -> TmIcons.Rewind
    VideoSort.Name -> TmIcons.TextSize
    VideoSort.Year -> TmIcons.Trailer
    VideoSort.Size -> TmIcons.Download
    VideoSort.Longest -> TmIcons.Frames
    VideoSort.LastPlayed -> TmIcons.History
    VideoSort.Unwatched -> TmIcons.PlayCircle
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

/** The glyph beside [filter] in the menu, an outline like every other line's. */
fun videoFilterIcon(filter: VideoFilter): ImageVector = when (filter) {
    VideoFilter.All -> TmIcons.Grid
    VideoFilter.Unwatched -> TmIcons.PlayCircle
    VideoFilter.InProgress -> TmIcons.Pause
    VideoFilter.Watched -> TmIcons.CheckCircle
    VideoFilter.OnDevice -> TmIcons.Folder
    VideoFilter.Shows -> TmIcons.Episodes
    VideoFilter.Singles -> TmIcons.Play
    VideoFilter.HighDefinition -> TmIcons.AspectRatio
}

/** What [length] is called in the Length group. */
fun videoLengthLabel(length: VideoFacets.Length, s: Messages): String = when (length) {
    VideoFacets.Length.Any -> s.gridFilterAny
    VideoFacets.Length.Short -> s.gridFilterLengthShort
    VideoFacets.Length.Long -> s.gridFilterLengthLong
}

/** What [picture] is called in the Picture group: at least that sharp. */
fun videoPictureLabel(picture: VideoFacets.Picture, s: Messages): String = when (picture) {
    VideoFacets.Picture.Any -> s.gridFilterAny
    VideoFacets.Picture.Uhd -> s.gridFilterPicture4k
    VideoFacets.Picture.FullHd -> s.gridFilterHd
    VideoFacets.Picture.Hd -> s.gridFilterPicture720
}

/** What [added] is called in the Added group. */
fun videoAddedLabel(added: VideoFacets.Added, s: Messages): String = when (added) {
    VideoFacets.Added.Any -> s.gridFilterAny
    VideoFacets.Added.Week -> s.gridFilterAddedWeek
    VideoFacets.Added.Month -> s.gridFilterAddedMonth
    VideoFacets.Added.Year -> s.gridFilterAddedYear
}

/** A language code ("ml") in the app's language ("Malayalam"), or the code when it has no name. */
fun videoLanguageLabel(code: String, s: Messages): String = s.formatter.trackLanguage(code) ?: code

/**
 * Every filter in force, in words, for the lines over the grid ("Unwatched, Under 20 minutes,
 * HDR"), or null when none is.
 */
fun videoFiltersLabel(filter: VideoFilter, facets: VideoFacets, s: Messages): String? = buildList {
    if (filter != VideoFilter.All) add(videoFilterLabel(filter, s))
    if (facets.length != VideoFacets.Length.Any) add(videoLengthLabel(facets.length, s))
    if (facets.picture != VideoFacets.Picture.Any) add(videoPictureLabel(facets.picture, s))
    if (facets.hdr) add(s.gridFilterHdr)
    if (facets.subtitles) add(s.gridFilterSubtitles)
    facets.language?.let { add(videoLanguageLabel(it, s)) }
    if (facets.added != VideoFacets.Added.Any) add(videoAddedLabel(facets.added, s))
}.takeIf { it.isNotEmpty() }?.joinToString(", ")

/**
 * What a chat's Sort menu holds, the same on a phone, a television and a computer: the order
 * ([VideoSort]), which videos show ([VideoFilter]) and the filters by what a video is
 * ([VideoFacets]: length, picture, HDR and subtitles, language, when added), which combine, and one
 * switch, "Hide small clips" ([onHideSmall], under [com.tmplayer.data.SizeFilter.CLIP_MIN]). Each
 * pick applies at once. The switch stays in the menu when pressed (the grid changes behind it); a
 * chat that hid something ends in a row that says so, and a floor chosen before the switch
 * existed gets its own line with a Reset ([customLimits]).
 *
 * @param languages the languages the loaded videos' names state ([VideoFaceting.languagesIn]);
 *   the Language group is left out while there are none.
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
    val facets: VideoFacets = VideoFacets.NONE,
    val languages: List<String> = emptyList(),
    val onFacets: (VideoFacets) -> Unit = {},
    /**
     * The "Hide small clips" switch: whether it reads on, and what turning it on or off does.
     * Null [onHideSmall] leaves the Size group out (a screen with no size limits to set).
     */
    val hideSmall: Boolean = false,
    val onHideSmall: ((Boolean) -> Unit)? = null,
    /**
     * A floor or ceiling chosen before the sliders went, kept working: the sentence for the line
     * under the switch ("You'll see videos from 700 MB upwards."), pressing which ([onResetLimits])
     * shows every video again. Null when there is none.
     */
    val customLimits: String? = null,
    val onResetLimits: (() -> Unit)? = null,
    /**
     * Play all (WP-32): the chat's videos from the first listed on, in the order the grid is sorted
     * in. Null leaves the line out: an empty list has nothing to start with.
     */
    val onPlayAll: (() -> Unit)? = null,
    /**
     * Shuffle (WP-87): the loaded videos in a seeded order, extended by pages. Null leaves the line
     * out, and so does a chat of one video.
     */
    val onShuffle: (() -> Unit)? = null,
)

/**
 * The menu's groups, as [GroupedChoicePopover] draws them and the television's sheet lists them:
 * Play random first where there is something to pick, then
 * Sort videos by, Show, Small videos (the one size switch, near the top so it is found), Length, Picture, With (HDR and Subtitles, each on or off), Language
 * and Added. "1080p and up" lives in Picture now; a chat that kept it under Show from an earlier
 * version still lists it there until another is picked.
 */
fun videoMenuGroups(state: VideoMenuState, s: Messages): List<ChoiceGroup> = buildList {
    // First, where the thumb and the remote land: one press away from the Sort button.
    val plays = listOfNotNull(
        state.onPlayAll?.let { play -> GroupedChoice(s.browsePlayAll, TmIcons.Play, false, play) },
        state.onShuffle?.let { play -> GroupedChoice(s.browseShuffle, TmIcons.Shuffle, false, play) },
        state.onPlayRandom?.let { play -> GroupedChoice(s.gridPlayRandom, TmIcons.Shuffle, false, play) },
    )
    if (plays.isNotEmpty()) add(ChoiceGroup(s.commonPlay, plays))
    val facets = state.facets
    val pick: (VideoFacets) -> Unit = state.onFacets
    add(ChoiceGroup(s.gridSortLabel, VideoSort.entries.map { sort -> GroupedChoice(videoSortLabel(sort, s), videoSortIcon(sort), sort == state.sort) { state.onSort(sort) } }))
    val shows = VideoFilter.entries.filter { it != VideoFilter.HighDefinition || state.filter == VideoFilter.HighDefinition }
    add(ChoiceGroup(s.gridFilterLabel, shows.map { filter -> GroupedChoice(videoFilterLabel(filter, s), videoFilterIcon(filter), filter == state.filter) { state.onFilter(filter) } }))
    state.onHideSmall?.let { toggle ->
        val size = com.tmplayer.data.SizeFilter.label(com.tmplayer.data.SizeFilter.CLIP_MIN)
        val lines = buildList {
            add(GroupedChoice(s.gridHideSmallClips(size), TmIcons.FilterHorizontal, state.hideSmall) { toggle(!state.hideSmall) })
            val custom = state.customLimits
            val reset = state.onResetLimits
            if (custom != null && reset != null) add(GroupedChoice(s.gridSizeLimitOn(custom), TmIcons.Close, true, reset))
        }
        add(ChoiceGroup(s.gridFilterSizeLabel, lines))
    }
    add(ChoiceGroup(s.gridFilterLengthLabel, VideoFacets.Length.entries.map { length ->
        GroupedChoice(videoLengthLabel(length, s), if (length == VideoFacets.Length.Any) TmIcons.Grid else TmIcons.Clock, facets.length == length) { pick(facets.copy(length = length)) }
    }))
    add(ChoiceGroup(s.gridFilterPictureLabel, VideoFacets.Picture.entries.map { picture ->
        GroupedChoice(videoPictureLabel(picture, s), if (picture == VideoFacets.Picture.Any) TmIcons.Grid else TmIcons.AspectRatio, facets.picture == picture) { pick(facets.copy(picture = picture)) }
    }))
    add(ChoiceGroup(s.gridFilterWithLabel, listOf(
        GroupedChoice(s.gridFilterHdr, TmIcons.Theme, facets.hdr) { pick(facets.copy(hdr = !facets.hdr)) },
        GroupedChoice(s.gridFilterSubtitles, TmIcons.Subtitles, facets.subtitles) { pick(facets.copy(subtitles = !facets.subtitles)) },
    )))
    val languages = state.languages.let { found -> facets.language?.let { if (it in found) found else listOf(it) + found } ?: found }
    if (languages.isNotEmpty()) {
        add(ChoiceGroup(s.gridFilterLanguageLabel, listOf(GroupedChoice(s.gridFilterAny, TmIcons.Grid, facets.language == null) { pick(facets.copy(language = null)) }) +
            languages.map { code -> GroupedChoice(videoLanguageLabel(code, s), TmIcons.Language, facets.language == code) { pick(facets.copy(language = code)) } }))
    }
    add(ChoiceGroup(s.gridFilterAddedLabel, VideoFacets.Added.entries.map { added ->
        GroupedChoice(videoAddedLabel(added, s), if (added == VideoFacets.Added.Any) TmIcons.Grid else TmIcons.History, facets.added == added) { pick(facets.copy(added = added)) }
    }))
}

/**
 * A chat's Sort and filter menu on a phone and a computer: one card grown out of the Sort button
 * ([GroupedChoicePopover]) with every group of [videoMenuGroups]. A pick applies and closes the
 * card, so the grid is seen to change under it.
 */
@Composable
fun VideoMenuPopover(open: Boolean, anchor: PopoverAnchor, state: VideoMenuState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    GroupedChoicePopover(open = open, anchor = anchor, groups = videoMenuGroups(state, s), onDismiss = onDismiss)
}
