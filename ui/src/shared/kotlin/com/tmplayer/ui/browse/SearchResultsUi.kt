package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HidingFilter
import com.tmplayer.data.NameInitial
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.data.SeriesShelf
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.PosterArt
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

// The search across every chat as the three platforms draw it (CP73): a result card, the chats
// stacked as avatars, the words for its states. The phone and the desktop use the card as it is;
// the television its poster. Taps, focus and keys are the caller's.

/** A chat's picture in a circle, its first letter where it has none. */
@Composable
fun ChatAvatar(chat: ChatBrief?, size: Dp, modifier: Modifier = Modifier) {
    MediaArt(
        miniThumbnail = chat?.miniThumbnail,
        thumbnailFileId = chat?.photoFileId ?: 0,
        modifier = modifier.size(size).clip(CircleShape),
    ) {
        Text(
            NameInitial.of(chat?.title).orEmpty(),
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
            color = Tone.muted,
        )
    }
}

/**
 * Up to three of [chatIds]' avatars, overlapping, then "and 4 more": where a video was posted,
 * at a glance. The names are what a screen reader says.
 */
@Composable
fun AvatarStack(
    chatIds: List<Long>,
    chats: Map<Long, ChatBrief>,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    max: Int = 3,
) {
    val s = LocalStrings.current
    val shown = chatIds.take(max)
    val more = chatIds.size - shown.size
    // Each name isolated, so a Hebrew chat between two English ones does not reorder the list.
    val names = chatIds.mapNotNull { chats[it]?.title }.joinToString(", ") { "\u2068$it\u2069" }
    Row(
        modifier.semantics { contentDescription = s.searchInChats(names) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(size + (size * 0.6f) * (shown.size - 1).coerceAtLeast(0)).height(size)) {
            shown.forEachIndexed { index, id ->
                ChatAvatar(
                    chats[id],
                    size,
                    Modifier
                        .offset(x = size * 0.6f * index)
                        .border(1.5.dp, Tone.background, CircleShape),
                )
            }
        }
        val single = shown.singleOrNull()?.let { chats[it]?.title }
        val label = when {
            more > 0 -> s.searchAndMore(more)
            single != null -> "\u2068$single\u2069"
            else -> null
        }
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The facts under a card's title: the year when the title does not already say it, the best
 * picture, the length, and the sizes from the smallest copy to the largest.
 */
fun resultFacts(group: ResultGroup): String {
    val title = GlobalSearch.titleOf(group)
    val year = if (group.isShow) null else SeriesShelf.episodeOf(group.lead).year?.toString()?.takeIf { it !in title }
    val episodes = if (group.isShow) L.searchEpisodes(group.episodeCount) else null
    val duration = group.lead.durationSec.takeIf { it > 0 && !group.isShow }?.let(MediaMapper::formatDuration)
    val size = group.sizeRange?.let { range -> L.messages.formatter.sizeSpan(range.first, range.last).ifEmpty { null } }
    return listOfNotNull(year, episodes, group.bestQuality, duration, size).joinToString("  ·  ")
}

/**
 * One result as a card a list holds: the 2:3 poster on the start side, then the full title (the
 * tile title, never cut down to a poster name), its facts, the chats it is in and how many copies
 * there are, with the watch progress along the poster's foot. [highlighted] is the keyboard's or
 * the remote's selection.
 */
@Composable
fun SearchResultCard(
    group: ResultGroup,
    chats: Map<Long, ChatBrief>,
    progress: (MediaItem) -> Float?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    posterWidth: Dp = 64.dp,
) {
    val s = LocalStrings.current
    val item = remember(group) { group.asItem() }
    val title = remember(group) { GlobalSearch.titleOf(group) }
    val facts = remember(group) { resultFacts(group) }
    val fraction = group.copies.firstNotNullOfOrNull { progress(it) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (highlighted) Tone.focusFill.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResultPoster(item, Modifier.width(posterWidth), fraction)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) {
                Text(facts, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarStack(group.chatIds, chats, Modifier.weight(1f, fill = false))
                // Files, as the page counts its versions: the same file in two chats is one.
                val files = remember(group.copies) { group.fileCount }
                if (files > 1 && !group.isShow) CopiesChip(files)
                if (group.copies.any { it.onDevice }) {
                    Icon(TmIcons.Download, contentDescription = s.searchOnDevice, tint = Tone.accent, modifier = Modifier.size(14.dp))
                }
                if (group.copies.all { !it.canBeSaved }) {
                    Icon(TmIcons.Lock, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** A result's 2:3 poster, with the watch progress along its foot. */
@Composable
fun ResultPoster(item: MediaItem, modifier: Modifier = Modifier, fraction: Float? = null) {
    val meta = rememberMeta(item)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(Corner.Small))) {
        PosterArt(
            miniThumbnail = item.miniThumbnail,
            thumbnailFileId = item.thumbnailFileId,
            cover = rememberCover(item),
            posterUrl = meta?.posterUrl,
            modifier = Modifier.fillMaxSize(),
            compactCover = true,
        )
        if (fraction != null && fraction > 0f) {
            ProgressLine(fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/** "3 copies", as a small pill. */
@Composable
fun CopiesChip(count: Int, modifier: Modifier = Modifier, onArt: Boolean = false) {
    val s = LocalStrings.current
    Box(
        modifier
            .clip(CircleShape)
            .background(if (onArt) Tone.scrim else Tone.surfaceHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(s.searchCopies(count), style = MaterialTheme.typography.labelSmall, color = if (onArt) Tone.onArt else Tone.text, maxLines = 1)
    }
}

/** A result card's shape while the first answer is on its way. */
@Composable
fun SearchResultSkeleton(modifier: Modifier = Modifier, posterWidth: Dp = 64.dp) {
    val shimmer = rememberShimmer()
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBox(shimmer, Modifier.width(posterWidth).aspectRatio(2f / 3f))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.7f).height(14.dp))
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.5f).height(10.dp))
            SkeletonBox(shimmer, Modifier.width(60.dp).height(16.dp), CircleShape)
        }
    }
}

/** The chip's words for a quality. */
val SearchQuality.label: String get() = tag ?: L.searchFilterAnyQuality

val SearchSort.label: String get() = when (this) {
    SearchSort.BestMatch -> L.searchSortBest
    SearchSort.Newest -> L.searchSortNewest
    SearchSort.BestQuality -> L.searchSortQuality
    SearchSort.Largest -> L.searchSortLargest
}

val SearchChatType.label: String get() = when (this) {
    SearchChatType.All -> L.browseFilterAll
    SearchChatType.Channels -> L.browseFilterChannels
    SearchChatType.Groups -> L.browseFilterGroups
    SearchChatType.People -> L.browseFilterPeople
}

/** What the empty state says a filter hid. */
fun HidingFilter.message(state: GlobalSearchState): String = when (this) {
    HidingFilter.Quality -> L.searchHiddenByQuality(
        state.filters.quality.tag ?: state.query.quality.tag.orEmpty(),
    )
    HidingFilter.SmallClips -> L.searchHiddenBySmall
    HidingFilter.ChatType -> L.searchHiddenByType(state.filters.chatType.label)
    HidingFilter.Archived -> L.searchHiddenByArchived
}

/**
 * The one line a search says about itself above or in place of its results, or null when the
 * results speak for themselves.
 */
fun searchStatus(state: GlobalSearchState): String? = when {
    state.floodWait > 0 -> L.searchFloodWait(state.floodWait)
    state.offline -> L.searchOffline
    state.error != null -> L.searchFailed(state.error)
    state.stillSearching -> L.searchStillSearching
    else -> null
}
