package com.tmplayer.ui.browse

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.CardLayout
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaFeedEntry
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredReportOption
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.WatchPoint
import com.tmplayer.data.isSponsoredTextFullyVisible
import com.tmplayer.data.placeSponsored
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay

@Suppress("UNCHECKED_CAST")
private class MediaListViewModelFactory(
    private val chatId: Long,
    private val minSize: Long,
    private val maxSize: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MediaListViewModel(chatId, minSize, maxSize) as T
}

@Composable
fun MediaGridScreen(
    chatId: Long,
    chatTitle: String,
    chatPhotoFileId: Int,
    chatMiniThumbnail: ByteArray?,
    isFavorite: Boolean,
    minSizeBytes: Long,
    maxSizeBytes: Long,
    watchProgress: Map<String, WatchPoint>,
    onToggleFavorite: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onToggleLayout: () -> Unit,
    telegramConnected: Boolean,
    offline: Boolean,
    onOfflineAction: (String) -> Unit,
    connectionNotice: ConnectionNotice,
    /** Thumbnails four across, or one wide row per video with the full title on it. */
    layout: CardLayout = CardLayout.Grid,
) {
    val context = LocalContext.current
    // A phone has neither overscan to clear nor a fixed width to plan a grid against, so both of
    // those figures are asked for again below rather than assumed.
    val touch = !FormFactor.isTv(context)
    val edge = if (touch) TOUCH_EDGE else Tv.SafeH
    // The limits are part of the key: changing them in Settings has to rebuild the listing,
    // not leave a stale one filtered by the old bounds.
    val viewModel: MediaListViewModel = viewModel(
        key = "media-$chatId-$minSizeBytes-$maxSizeBytes",
        factory = MediaListViewModelFactory(chatId, minSizeBytes, maxSizeBytes),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    // Whatever the remote is standing on, for the name strip along the bottom.
    var standingOn by remember { mutableStateOf<MediaItem?>(null) }
    val connectionOffset = if (connectionNotice == ConnectionNotice.Hidden) 0.dp else 56.dp
    var reconnectPending by remember(chatId) { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<SponsoredItem?>(null) }
    var reportTitle by remember { mutableStateOf("") }
    var reportOptions by remember { mutableStateOf<List<SponsoredReportOption>>(emptyList()) }

    fun handleReport(item: SponsoredItem, optionId: ByteArray = byteArrayOf()) {
        viewModel.reportSponsored(
            item = item,
            optionId = optionId,
            onResult = { outcome ->
                when (outcome) {
                    is SponsoredReportOutcome.Options -> {
                        reportTarget = item
                        reportTitle = outcome.title
                        reportOptions = outcome.options
                    }
                    SponsoredReportOutcome.Reported -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction("Sponsored message reported.")
                    }
                    SponsoredReportOutcome.AdsHidden -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction("Sponsored messages hidden by Telegram.")
                    }
                    SponsoredReportOutcome.PremiumRequired -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction("Telegram Premium is required to hide sponsored messages.")
                    }
                    SponsoredReportOutcome.Unavailable -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction("This sponsored message can no longer be reported.")
                    }
                }
            },
            onFailure = onOfflineAction,
        )
    }

    fun openSponsored(item: SponsoredItem, media: Boolean) {
        viewModel.clickSponsored(
            item = item,
            media = media,
            onSuccess = {
                if (item.sponsorUrl.isNotBlank()) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sponsorUrl)))
                    }.onFailure { onOfflineAction("No app can open this sponsored link.") }
                }
            },
            onFailure = onOfflineAction,
        )
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocalAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(telegramConnected) {
        if (!telegramConnected) {
            reconnectPending = true
        } else if (reconnectPending) {
            reconnectPending = false
            viewModel.load()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            chatTitle = chatTitle,
            chatPhotoFileId = chatPhotoFileId,
            chatMiniThumbnail = chatMiniThumbnail,
            isFavorite = isFavorite,
            query = query,
            onQuery = { query = it },
            onSubmit = { viewModel.search(query) },
            onToggleFavorite = onToggleFavorite,
            layout = layout,
            edge = edge,
            onToggleLayout = onToggleLayout,
            onRefresh = {
                viewModel.load()
                if (offline) onOfflineAction("You're offline. Showing saved videos.")
            },
        )

        StateScaffold(
            state,
            onRetry = viewModel::load,
            loading = { MediaGridSkeleton(layout = layout) },
        ) { list ->
            // The grid's column count is the one thing on this screen that cannot be a constant on
            // a phone: it has to follow the width, and the width follows which way up the phone is
            // being held. Everything that counts in columns, the paging lead included, reads it
            // from here so there is only ever one figure in play.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (touch) {
                ((maxWidth - edge * 2) / TOUCH_TILE_MIN).toInt().coerceAtLeast(1)
            } else {
                COLUMNS
            }
            val feed = remember(list.items, list.sponsored) {
                placeSponsored(list.items, list.sponsored)
            }
            val gridState = rememberLazyGridState()
            val listState = rememberLazyListState()
            val firstItem = remember { FocusRequester() }
            fun focusOf(item: MediaItem): Modifier =
                if (item === list.items.firstOrNull()) Modifier.focusRequester(firstItem) else Modifier

            val padding = PaddingValues(
                start = edge,
                end = edge,
                // A television crops its outermost few percent, so the last row needs
                // clearance or its titles are cut off the bottom of the panel.
                bottom = if (touch) 16.dp else Tv.SafeV + 16.dp,
            )

            // The strip only belongs there while the remote is somewhere in the listing, and a
            // move between two cards drops focus for a frame before the next card takes it. The
            // wait absorbs that gap, so stepping along a row does not make the strip blink.
            var listHasFocus by remember { mutableStateOf(false) }
            LaunchedEffect(listHasFocus) {
                if (!listHasFocus) {
                    delay(FOCUS_SETTLE_MS)
                    standingOn = null
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { listHasFocus = it.hasFocus },
            ) {
                when (layout) {
                    CardLayout.Grid -> LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItems(
                            items = feed,
                            key = {
                                when (it) {
                                    is MediaFeedEntry.Media -> "media-${it.item.id}"
                                    is MediaFeedEntry.Sponsored -> "sponsor-${it.item.messageId}"
                                }
                            },
                            span = {
                                if (it is MediaFeedEntry.Sponsored) GridItemSpan(maxLineSpan)
                                else GridItemSpan(1)
                            },
                        ) { entry ->
                            when (entry) {
                                is MediaFeedEntry.Media -> {
                                    val item = entry.item
                                    MediaCard(
                                        item = item,
                                        watched = watchProgress[
                                            SettingsStore.progressKey(item.chatId, item.messageId),
                                        ],
                                        onClick = { onPlay(item) },
                                        onFocused = { standingOn = item },
                                        modifier = focusOf(item),
                                    )
                                }
                                is MediaFeedEntry.Sponsored -> SponsoredCard(
                                    item = entry.item,
                                    onFullyVisible = {
                                        viewModel.markSponsoredViewed(entry.item.messageId)
                                    },
                                    onOpen = { openSponsored(entry.item, false) },
                                    onOpenMedia = { openSponsored(entry.item, true) },
                                    onReport = { handleReport(entry.item) },
                                )
                            }
                        }
                    }

                    CardLayout.List -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = feed,
                            key = {
                                when (it) {
                                    is MediaFeedEntry.Media -> "media-${it.item.id}"
                                    is MediaFeedEntry.Sponsored -> "sponsor-${it.item.messageId}"
                                }
                            },
                        ) { entry ->
                            when (entry) {
                                is MediaFeedEntry.Media -> {
                                    val item = entry.item
                                    MediaRow(
                                        item = item,
                                        watched = watchProgress[
                                            SettingsStore.progressKey(item.chatId, item.messageId),
                                        ],
                                        onClick = { onPlay(item) },
                                        onFocused = { standingOn = item },
                                        modifier = focusOf(item),
                                    )
                                }
                                is MediaFeedEntry.Sponsored -> SponsoredCard(
                                    item = entry.item,
                                    onFullyVisible = {
                                        viewModel.markSponsoredViewed(entry.item.messageId)
                                    },
                                    onOpen = { openSponsored(entry.item, false) },
                                    onOpenMedia = { openSponsored(entry.item, true) },
                                    onReport = { handleReport(entry.item) },
                                )
                            }
                        }
                    }
                }

                // Floated over the grid; a reserved band cost a whole row on a 540dp panel.
                if (list.loadingMore) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            // Above the name strip when there is one, rather than through it.
                            .padding(
                                bottom = if (standingOn != null) {
                                    Tv.SafeV + 46.dp + connectionOffset
                                } else {
                                    Tv.SafeV + connectionOffset
                                },
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceRaised)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Spinner(size = 18.dp, strokeWidth = 2.dp)
                        Text(
                            "Loading more…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                }

                standingOn?.let { item ->
                    FullName(
                        name = item.title,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = Tv.SafeH, bottom = Tv.SafeV + connectionOffset),
                    )
                }
            }

            // Switching arrangement rebuilds the list, taking the focused card with it, so the
            // first item has to be asked for again or the remote is left with nowhere to go.
            // Only on a remote. A phone has no focus to place, and asking for it there only puts a
            // highlight on a card nobody pointed at.
            LaunchedEffect(list.items.firstOrNull()?.id, layout, touch) {
                if (!touch) runCatching { firstItem.requestFocus() }
            }

            // Fetch the next page well before the user reaches the bottom row. The lead is counted
            // in items, so it has to follow the arrangement: two rows of the grid is eight videos,
            // while two rows of the list is two.
            val nearEnd by remember(list.items.size, layout, columns) {
                derivedStateOf {
                    // The two states report the same figure through unrelated item types, so the
                    // index comes out of each branch rather than the item it came from.
                    val last = when (layout) {
                        CardLayout.Grid ->
                            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        CardLayout.List ->
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    } ?: 0
                    val lead = when (layout) {
                        CardLayout.Grid -> columns * 2
                        CardLayout.List -> LIST_LEAD
                    }
                    last >= feed.size - lead
                }
            }
            LaunchedEffect(gridState, listState, list.items.size, layout) {
                snapshotFlow { nearEnd }.collect { if (it) viewModel.loadMore() }
            }
            }
        }
    }

    val target = reportTarget
    if (target != null && reportOptions.isNotEmpty()) {
        TvMenu(
            title = reportTitle.ifBlank { "Report sponsored message" },
            subtitle = "Telegram decides what happens after your report",
            actions = reportOptions.map { option ->
                MenuAction(
                    label = option.text,
                    icon = Icons.Filled.Close,
                    onSelect = { handleReport(target, option.id) },
                )
            },
            onDismiss = {
                reportTarget = null
                reportOptions = emptyList()
            },
        )
    }
}

@Composable
internal fun Header(
    chatTitle: String,
    chatPhotoFileId: Int,
    chatMiniThumbnail: ByteArray?,
    isFavorite: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleFavorite: () -> Unit,
    layout: CardLayout,
    /** How far in from the side the header starts, which is overscan on a TV and taste on a phone. */
    edge: Dp,
    onToggleLayout: () -> Unit,
    onRefresh: () -> Unit,
) {
    val startVoice = rememberVoiceSearch("Say a video name") {
        onQuery(it)
        onSubmit()
    }

    Column(Modifier.padding(start = edge, end = edge, top = Tv.SafeV, bottom = 12.dp)) {
        // The same picture the chat was picked by, so it is obvious which one this listing is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaPreview(
                miniThumbnail = chatMiniThumbnail,
                thumbnailFileId = chatPhotoFileId,
                fallbackLabel = chatTitle,
                modifier = Modifier.size(52.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    chatTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Videos from this chat",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val searchField = remember { FocusRequester() }

            TvSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search this chat",
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f).focusRequester(searchField),
            )
            if (startVoice != null) {
                // The microphone says what it does; the word beside it only took room from the
                // search field.
                Pill(label = "Voice search", icon = TmIcons.Mic, showLabel = false, onClick = startVoice)
            }
            if (query.isNotBlank()) {
                Pill("Clear", Icons.Filled.Close) {
                    // Focus has to leave before the state change, because clearing the query is
                    // what removes this pill from the layout. Letting it vanish while focused
                    // strands the remote: the next press goes nowhere or jumps to the rail.
                    runCatching { searchField.requestFocus() }
                    onQuery("")
                    onSubmit()
                }
            }
            Pill(
                label = if (isFavorite) "Remove favourite" else "Add favourite",
                icon = if (isFavorite) Icons.Filled.Star else TmIcons.StarOutline,
                tintWhenIdle = if (isFavorite) Accent else TextPrimary,
                onClick = onToggleFavorite,
            )
            // Which arrangement suits a chat depends on the chat: tiles for visual browsing,
            // rows for one that posts long file names. The choice is remembered per screen.
            Pill(
                label = if (layout == CardLayout.Grid) "As rows" else "As tiles",
                icon = if (layout == CardLayout.Grid) Icons.AutoMirrored.Filled.List else TmIcons.Grid,
                // The two glyphs are the ones every app uses for this; the words repeated them.
                showLabel = false,
                onClick = onToggleLayout,
            )
            // Telegram pushes new messages into TDLib's database, but this grid was built from a
            // search that ran when it opened, so a video posted since then needs a fresh search.
            Pill("Refresh", Icons.Filled.Refresh, onClick = onRefresh)
        }
    }
}

/**
 * Telegram-sponsored content stays visually and behaviorally separate from playable media.
 * The complete disclosure and message text are measured as one block and only marked viewed once
 * that entire block is inside the TV viewport.
 */
@Composable
private fun SponsoredCard(
    item: SponsoredItem,
    onFullyVisible: () -> Unit,
    onOpen: () -> Unit,
    onOpenMedia: () -> Unit,
    onReport: () -> Unit,
) {
    var textTop by remember(item.messageId) { mutableStateOf(Float.NaN) }
    var textBottom by remember(item.messageId) { mutableStateOf(Float.NaN) }
    var viewportHeight by remember(item.messageId) { mutableStateOf(0f) }
    val fullyVisible = remember(textTop, textBottom, viewportHeight) {
        !textTop.isNaN() && !textBottom.isNaN() &&
            isSponsoredTextFullyVisible(textTop, textBottom, viewportHeight)
    }
    LaunchedEffect(item.messageId, fullyVisible) {
        if (fullyVisible) onFullyVisible()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised)
            .border(2.dp, Caution.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.miniThumbnail != null || item.thumbnailFileId != 0) {
            val mediaInteractions = remember { MutableInteractionSource() }
            val mediaFocused by mediaInteractions.collectIsFocusedAsState()
            MediaPreview(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title.ifBlank { item.label },
                modifier = Modifier
                    .width(190.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(9.dp))
                    .border(
                        3.dp,
                        if (mediaFocused) Accent else Color.Transparent,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable(
                        interactionSource = mediaInteractions,
                        indication = null,
                        onClick = onOpenMedia,
                    ),
            )
        }

        Column(
            Modifier
                .weight(1f)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    textTop = bounds.top
                    textBottom = bounds.bottom
                    viewportHeight = coordinates.findRootCoordinates().size.height.toFloat()
                },
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                item.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Caution,
            )
            if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
            }
            if (item.text.isNotBlank()) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            }
            if (item.additionalInfo.isNotBlank()) {
                Text(
                    item.additionalInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
            if (item.sponsorInfo.isNotBlank()) {
                Text(
                    item.sponsorInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.buttonText.isNotBlank()) {
                SponsoredButton(item.buttonText, Accent, onOpen)
            }
            if (item.canBeReported) {
                SponsoredButton("Report", SurfaceDark, onReport)
            }
        }
    }
}

@Composable
private fun SponsoredButton(label: String, idleColor: Color, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Color.White else idleColor,
        animationSpec = tween(140),
        label = "sponsoredButton",
    )
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (focused) Color.Black else TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * [label] is always given, even where [showLabel] hides it: it is what a screen reader announces,
 * and an icon with no name is a button nobody can identify.
 */
@Composable
private fun Pill(
    label: String,
    icon: ImageVector,
    tintWhenIdle: Color = TextPrimary,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceRaised,
        animationSpec = tween(140),
        label = "pill",
    )
    val foreground = if (focused) Color.White else tintWhenIdle

    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            // Even padding when there are no words, so the pill comes out round rather than as a
            // wide one with a gap in it.
            .padding(horizontal = if (showLabel) 16.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(22.dp))
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
        }
    }
}

/**
 * The whole name of whatever the remote is standing on, along the bottom corner.
 *
 * Both arrangements have to cut the name short: a tile has a quarter of the width and a row has
 * two lines of it, and a release name beats either. This is the browser's trick of putting the
 * link under the cursor in the corner of the window. It stays out of the way of the listing, and
 * a name too long even for half the screen scrolls past instead of ending in an ellipsis.
 */
@Composable
private fun FullName(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = STRIP_MAX_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            softWrap = false,
            // Long enough to read a whole line before it starts moving, and again each time it
            // comes back round.
            modifier = Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_500,
                repeatDelayMillis = 1_500,
                velocity = 32.dp,
            ),
        )
    }
}

/**
 * A media tile that marks focus with a border rather than by growing.
 *
 * TV Material's card scales up when focused, and a card in the outermost grid column visibly
 * runs off the screen edge when it does.
 */
@Composable
internal fun MediaCard(
    item: MediaItem,
    watched: WatchPoint?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(140),
        label = "cardBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
    ) {
        MediaArt(item, watched, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                // One line, not two. A release file name is long enough to fill both, and the
                // second line cost a row of the grid on a 540dp panel; the full title is one
                // press away during playback now.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            MetaLine(item, watched)
        }
    }
}

/**
 * The same video as a full-width row.
 *
 * This is the arrangement for a chat full of release file names: the title gets the whole width of
 * the panel rather than a quarter of it, so a name that a media tile cuts after four words is
 * readable without opening anything.
 */
@Composable
private fun MediaRow(
    item: MediaItem,
    watched: WatchPoint?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(140),
        label = "rowBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArt(
            item,
            watched,
            Modifier
                .width(ROW_ART_WIDTH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                // Two lines here, unlike the tile: this is the arrangement someone picked in
                // order to read the name, and a row can afford the height a grid cell cannot.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(item, watched)
        }
    }
}

/** A media preview with its quality tag and saved playback progress. */
@Composable
private fun MediaArt(item: MediaItem, watched: WatchPoint?, modifier: Modifier = Modifier) {
    Box(modifier) {
        MediaPreview(
            miniThumbnail = item.miniThumbnail,
            thumbnailFileId = item.thumbnailFileId,
            fallbackLabel = item.title,
            modifier = Modifier.fillMaxSize(),
        )
        val tags = item.qualityTags
        if (item.onDevice) {
            Text(
                "On this TV",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Accent.copy(alpha = 0.92f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (tags.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (watched != null && watched.fraction > 0f) {
            // A thin bar along the bottom of the art, the one place a viewer already looks
            // to see whether they have started something.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(watched.fraction)
                        .fillMaxHeight()
                        .background(Accent),
                )
            }
        }
    }
}

/** Running time, file size, and where playback stopped, on the one line both arrangements use. */
@Composable
private fun MetaLine(item: MediaItem, watched: WatchPoint?) {
    val duration = MediaMapper.formatDuration(item.durationSec)
    val size = MediaMapper.formatSize(item.sizeBytes)
    val resume = watched
        ?.takeIf { it.positionMs > 0 }
        ?.let { "Stopped at ${com.tmplayer.player.StreamStats.formatClock(it.positionMs)}" }
    Text(
        listOfNotNull(duration.ifEmpty { null }, size.ifEmpty { null }, resume)
            .joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        color = if (resume != null) Accent else TextMuted,
        maxLines = 1,
    )
}

private const val COLUMNS = 4

/** The narrowest a video tile may be on a phone before the grid drops a column. */
private val TOUCH_TILE_MIN = 168.dp

/** No overscan to clear on a phone, so the margin is only what keeps art off the bezel. */
private val TOUCH_EDGE = 16.dp

/** How many rows from the bottom the next page is fetched in the list arrangement. */
private const val LIST_LEAD = 4

private val ROW_ART_WIDTH = 176.dp

/** Half the panel, so the strip never reaches back across the listing it belongs to. */
private val STRIP_MAX_WIDTH = 480.dp

/** How long a gap in focus has to last before the name strip counts it as having left. */
private const val FOCUS_SETTLE_MS = 150L
