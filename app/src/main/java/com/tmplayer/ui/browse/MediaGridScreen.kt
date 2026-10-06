package com.tmplayer.ui.browse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.CardLayout
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaFeedEntry
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredReportOption
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.Td
import com.tmplayer.data.WatchPoint
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.cancel
import com.tmplayer.data.isSponsoredTextFullyVisible
import com.tmplayer.data.placeSponsored
import com.tmplayer.data.start
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressable
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.focusScale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A store that lives exactly as long as one media screen, so its view models are released with it. */
private class MediaScreenStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Suppress("UNCHECKED_CAST")
private class MediaListViewModelFactory(
    private val chatId: Long,
    private val minSize: Long,
    private val maxSize: Long,
    private val settings: SettingsStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        // The download index, so a downloaded video's tile says Downloaded rather than Cached.
        MediaListViewModel(
            chatId,
            minSize,
            maxSize,
            onSearched = { settings.addRecentSearch(it) },
        ) { LocalDownloads.presentIds(settings) } as T
}

@OptIn(ExperimentalMaterial3Api::class)
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
    /**
     * The Watched list keyed like [watchProgress], for the tick on a finished video's tile. Empty
     * by default, which draws every tile as it was before the list existed.
     */
    watchedVideos: Map<String, WatchedRecord> = emptyMap(),
    /** "Mark as watched" (true) or "Mark as unwatched" (false) from a tile's menu. */
    onSetWatched: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onToggleFavorite: () -> Unit,
    /** Leaving the chat. On a phone this is the app bar's arrow as well as the hardware key. */
    onBack: () -> Unit = {},
    onPlay: (MediaItem) -> Unit,
    onToggleLayout: () -> Unit,
    telegramConnected: Boolean,
    offline: Boolean,
    onOfflineAction: (String) -> Unit,
    connectionNotice: ConnectionNotice,
    /** Thumbnails four across, or one wide row per video with the full title on it. */
    layout: CardLayout = CardLayout.Grid,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    // A phone has neither overscan to clear nor a fixed width to plan a grid against, so both of
    // those figures are asked for again below rather than assumed.
    val touch = !FormFactor.isTv(context)
    val edge = if (touch) TOUCH_EDGE else Tv.SafeH
    // The limits are part of the key: changing them in Settings has to rebuild the listing,
    // not leave a stale one filtered by the old bounds. Scoped to this screen rather than to the
    // activity, so a session's chats do not each leave behind an item list full of minithumbnail
    // byte arrays. The cost is one re-listing when a chat is reopened.
    val owner = remember(chatId, minSizeBytes, maxSizeBytes) { MediaScreenStore() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val viewModel: MediaListViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "media-$chatId-$minSizeBytes-$maxSizeBytes",
        factory = MediaListViewModelFactory(chatId, minSizeBytes, maxSizeBytes, SettingsStore(context)),
    )
    // A download leaving the queue has usually just landed in Downloads, so the tiles are asked
    // again: the badge on it moves from the queue's figure to Downloaded.
    val queueSize by remember { OfflineDownloads.active.map { it.size }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 0)
    LaunchedEffect(queueSize) { viewModel.refreshLocalAvailability() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    val recentStore = remember(context) { SettingsStore(context) }
    val recentSearches by recentStore.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentScope = rememberCoroutineScope()
    val clearRecent: () -> Unit = { recentScope.launch { recentStore.clearRecentSearches() } }
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
                        onOfflineAction(s.gridSponsoredReported)
                    }
                    SponsoredReportOutcome.AdsHidden -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction(s.gridSponsoredHidden)
                    }
                    SponsoredReportOutcome.PremiumRequired -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction(s.gridSponsoredPremium)
                    }
                    SponsoredReportOutcome.Unavailable -> {
                        reportTarget = null
                        reportOptions = emptyList()
                        onOfflineAction(s.gridSponsoredUnavailable)
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
                    }.onFailure { onOfflineAction(s.gridSponsoredNoApp) }
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

    val refresh = {
        viewModel.load()
        if (offline) onOfflineAction(s.gridOfflineRefresh)
    }

    // Whichever video a long press is asking about, and nothing while none is.
    var showingDetailsOf by remember(chatId) { mutableStateOf<MediaItem?>(null) }

    // The videos ticked for one batch download, held as the items themselves rather than as ids:
    // the download is worked out and started well after the tick, by which time paging may have
    // handed the listing a fresh list, and an id with nothing to look it up in is not a video.
    var selected by remember(chatId) { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }
    var selecting by remember(chatId) { mutableStateOf(false) }
    // What "Select all" means, which is every video the listing has fetched so far. The bar lives
    // above the listing and cannot see inside it, so the loaded page is passed out to here.
    var listedItems by remember(chatId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val settings = remember(context) { SettingsStore(context) }
    // "Series" folds a show's episodes into one tile; "All files" lists every video as before.
    val seriesView by settings.seriesView.collectAsStateWithLifecycle(initialValue = true)
    val watch = remember(watchProgress, watchedVideos) {
        SeriesWatch(
            point = { watchProgress[SettingsStore.progressKey(it.chatId, it.messageId)] },
            finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedVideos },
        )
    }
    // The show opened from its tile, by key, so it keeps up as more of its episodes page in.
    var openSeries by remember(chatId) { mutableStateOf<String?>(null) }

    fun leaveSelection() {
        selecting = false
        selected = emptyMap()
    }

    fun toggle(item: MediaItem) {
        selected = if (selected.containsKey(item.id)) selected - item.id else selected + (item.id to item)
    }

    // Only while there is a selection to leave, so Back still leaves the chat the rest of the time.
    BackHandler(enabled = selecting) { leaveSelection() }

    /**
     * Queues videos, having first asked the disk whether they fit: see [queueDownloads], which
     * every route in goes through, the detail panel's Download included.
     */
    fun downloadThese(ticked: List<MediaItem>) {
        // A chat that restricts saving content may be watched here but not kept, so its videos
        // drop out of a selection quietly; the bar does not offer Download when only they are left.
        val chosen = ticked.filter { it.canBeSaved }
        if (chosen.isEmpty()) return
        leaveSelection()
        scope.launch {
            queueDownloads(context, settings, chosen) { chatTitle }?.let(onOfflineAction)
        }
    }

    // A refresh that keeps its content stays a Content state throughout, so the pull gesture waits
    // on a new state instance instead. The timeout stops the spinner turning for ever when a
    // request against a dead connection never emits at all.
    var refreshing by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        val before = viewModel.state.value
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) { viewModel.state.first { it !== before } }
        refreshing = false
    }

    val listing: @Composable () -> Unit = {
        StateScaffold(
            state,
            onRetry = viewModel::load,
            loading = { MediaGridSkeleton(layout = layout) },
            onAction = viewModel::act,
        ) { list ->
            // On a phone the column count follows the width, which follows the orientation.
            // Everything that counts in columns, the paging lead included, reads it from here so
            // there is only ever one figure in play.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (touch) {
                ((maxWidth - edge * 2) / TOUCH_TILE_MIN).toInt().coerceAtLeast(1)
            } else {
                COLUMNS
            }
            // Folded only while browsing: a search ranks files against what was typed, and picking
            // videos to download picks files, so both see every file on its own.
            val arranged = remember(list.items) { SeriesShelf.arrange(list.items) }
            val hasShows = arranged.any { it is ShelfEntry.Show }
            val grouping = seriesView && hasShows && !selecting && query.isBlank()
            val shelf = remember(arranged, list.items, grouping) {
                if (grouping) arranged else list.items.map { ShelfEntry.File(it) }
            }
            val feed = remember(shelf, list.sponsored) {
                placeSponsored(shelf, list.sponsored)
            }
            val viewSwitch: @Composable () -> Unit = {
                SeriesViewToggle(
                    seriesView = seriesView,
                    onChange = { on -> scope.launch { settings.setSeriesView(on) } },
                    modifier = Modifier.padding(start = if (touch) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            LaunchedEffect(list.items) { listedItems = list.items }
            val gridState = rememberLazyGridState()
            val listState = rememberLazyListState()
            val firstItem = remember { FocusRequester() }
            val firstKey = shelf.firstOrNull()?.key
            fun focusOf(entry: ShelfEntry): Modifier =
                if (entry.key == firstKey) Modifier.focusRequester(firstItem) else Modifier

            // The phone's grid is compact but not captionless: smaller art than a television's
            // card, two lines of the file name under it in small type, and a hairline of a gap.
            // The name is often the only thing telling two releases apart.
            val dense = touch && layout == CardLayout.Grid
            val gap = if (dense) DENSE_GAP else 16.dp
            val padding = PaddingValues(
                start = if (dense) DENSE_GAP else edge,
                end = if (dense) DENSE_GAP else edge,
                // Room above the first row for a focused tile to grow into.
                top = if (touch) 0.dp else Tv.FocusClearance,
                // A television crops its outermost few percent, so the last row needs
                // clearance or its titles are cut off the bottom of the panel. A phone crops
                // nothing but does put a gesture bar over the last row.
                bottom = if (touch) navigationBarPadding() + 16.dp else Tv.SafeV + 16.dp,
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
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        if (hasShows && !selecting && query.isBlank()) {
                            item(key = "series-toggle", span = { GridItemSpan(maxLineSpan) }) { viewSwitch() }
                        }
                        if (list.hiddenBySize > 0 || list.hiddenSelfDestructing > 0) {
                            item(key = "hidden-videos", span = { GridItemSpan(maxLineSpan) }) {
                                HiddenVideosNote(list.hiddenBySize, list.hiddenSelfDestructing, viewModel::showHidden)
                            }
                        }
                        gridItems(
                            items = feed,
                            key = {
                                when (it) {
                                    is MediaFeedEntry.Media -> it.item.key
                                    is MediaFeedEntry.Sponsored -> "sponsor-${it.item.messageId}"
                                }
                            },
                            span = {
                                if (it is MediaFeedEntry.Sponsored) GridItemSpan(maxLineSpan)
                                else GridItemSpan(1)
                            },
                        ) { entry ->
                            when (entry) {
                                is MediaFeedEntry.Media -> when (val shelved = entry.item) {
                                    is ShelfEntry.Show -> SeriesCard(
                                        series = shelved.series,
                                        progress = watch.progress(shelved.series),
                                        onClick = { openSeries = shelved.series.key },
                                        onFocused = { standingOn = null },
                                        modifier = focusOf(shelved),
                                        dense = dense,
                                    )
                                    is ShelfEntry.File -> {
                                    val item = shelved.item
                                    MediaCard(
                                        item = item,
                                        watched = watchProgress[
                                            SettingsStore.progressKey(item.chatId, item.messageId),
                                        ],
                                        finished = SettingsStore.progressKey(item.chatId, item.messageId) in watchedVideos,
                                        dense = dense,
                                        selected = if (selecting) selected.containsKey(item.id) else null,
                                        onClick = { if (selecting) toggle(item) else onPlay(item) },
                                        onLongClick = if (selecting) null else {
                                            { showingDetailsOf = item }
                                        },
                                        onFocused = { standingOn = item },
                                        modifier = focusOf(shelved),
                                    )
                                    }
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
                        if (hasShows && !selecting && query.isBlank()) {
                            item(key = "series-toggle") { viewSwitch() }
                        }
                        if (list.hiddenBySize > 0 || list.hiddenSelfDestructing > 0) {
                            item(key = "hidden-videos") {
                                HiddenVideosNote(list.hiddenBySize, list.hiddenSelfDestructing, viewModel::showHidden)
                            }
                        }
                        items(
                            items = feed,
                            key = {
                                when (it) {
                                    is MediaFeedEntry.Media -> it.item.key
                                    is MediaFeedEntry.Sponsored -> "sponsor-${it.item.messageId}"
                                }
                            },
                        ) { entry ->
                            when (entry) {
                                is MediaFeedEntry.Media -> when (val shelved = entry.item) {
                                    is ShelfEntry.Show -> SeriesListRow(
                                        series = shelved.series,
                                        progress = watch.progress(shelved.series),
                                        onClick = { openSeries = shelved.series.key },
                                        onFocused = { standingOn = null },
                                        modifier = focusOf(shelved),
                                    )
                                    is ShelfEntry.File -> {
                                    val item = shelved.item
                                    MediaRow(
                                        item = item,
                                        watched = watchProgress[
                                            SettingsStore.progressKey(item.chatId, item.messageId),
                                        ],
                                        finished = SettingsStore.progressKey(item.chatId, item.messageId) in watchedVideos,
                                        selected = if (selecting) selected.containsKey(item.id) else null,
                                        onClick = { if (selecting) toggle(item) else onPlay(item) },
                                        onLongClick = if (selecting) null else {
                                            { showingDetailsOf = item }
                                        },
                                        onFocused = { standingOn = item },
                                        modifier = focusOf(shelved),
                                    )
                                    }
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

                // Before the video menu, so a held episode's menu draws over the show.
                openSeries?.let { key ->
                    val series = arranged.firstNotNullOfOrNull { (it as? ShelfEntry.Show)?.series?.takeIf { s -> s.key == key } }
                    if (series == null) {
                        LaunchedEffect(key) { openSeries = null }
                    } else {
                        SeriesOpened(
                            series = series,
                            watch = watch,
                            onPlay = onPlay,
                            onDismiss = { openSeries = null },
                            onLongClick = { showingDetailsOf = it },
                        )
                    }
                }

                showingDetailsOf?.let { item ->
                    MediaDetailOpened(
                        item = item,
                        chatTitle = chatTitle,
                        watched = watchProgress[
                            SettingsStore.progressKey(item.chatId, item.messageId),
                        ],
                        finished = SettingsStore.progressKey(item.chatId, item.messageId) in watchedVideos,
                        onSetWatched = { onSetWatched(item, it) },
                        onPlay = { onPlay(item) },
                        onSelectVideos = {
                            selected = mapOf(item.id to item)
                            selecting = true
                        },
                        onDownload = { downloadThese(listOf(item)) },
                        onRemoved = viewModel::refreshLocalAvailability,
                        onDismiss = { showingDetailsOf = null },
                    )
                }

                // Floated over the grid; a reserved band cost a whole row on a 540dp panel.
                if (list.loadingMore) {
                    Surface(
                        shape = CircleShape,
                        color = Tone.surfaceHigh,
                        contentColor = Tone.muted,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Above the name strip when there is one, rather than through it.
                            .padding(
                                // Tv.SafeV is overscan clearance and means nothing on a phone,
                                // where the gesture bar is the real obstacle.
                                bottom = (if (touch) navigationBarPadding() else Tv.SafeV) +
                                    (if (standingOn != null) 46.dp else 0.dp) +
                                    connectionOffset,
                            ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Spinner(size = 18.dp, strokeWidth = 2.dp)
                            Text(
                                s.gridLoadingMore,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tone.muted,
                            )
                        }
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
            // first item has to be asked for again or the remote is left with nowhere to go. Only
            // on a remote: on a phone this would highlight a card nobody pointed at.
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

    // Null while every ticked video comes from a chat that restricts saving content.
    val downloadSelected = { downloadThese(selected.values.toList()) }
        .takeIf { selected.isEmpty() || selected.values.any { it.canBeSaved } }

    if (touch) {
        TouchMediaScaffold(
            chatTitle = chatTitle,
            chatPhotoFileId = chatPhotoFileId,
            chatMiniThumbnail = chatMiniThumbnail,
            isFavorite = isFavorite,
            query = query,
            onQuery = { query = it },
            onSubmit = { viewModel.search(query) },
            onBack = onBack,
            onToggleFavorite = onToggleFavorite,
            layout = layout,
            onToggleLayout = onToggleLayout,
            onRefresh = refresh,
            recentSearches = recentSearches,
            onClearRecent = clearRecent,
            selectionBar = if (!selecting) null else {
                {
                    SelectionBar(
                        count = selected.size,
                        onDownload = downloadSelected,
                        onSelectAll = { selected = listedItems.associateBy { it.id } },
                        onCancel = { leaveSelection() },
                        edge = edge,
                    )
                }
            },
            content = {
                // The circular arrow stays in the overflow too, because a listing that is empty or
                // in an error state has nothing to drag.
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    listing()
                }
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            // The bar takes the heading's place rather than sitting under it. The remote reaches it
            // by pressing Up out of the grid, which is the one move that already had a meaning
            // there, and the search field it covers is no use while videos are being ticked.
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onDownload = downloadSelected,
                    onSelectAll = { selected = listedItems.associateBy { it.id } },
                    onCancel = { leaveSelection() },
                    edge = edge,
                )
            } else {
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
                    onRefresh = refresh,
                    onBack = onBack,
                    recentSearches = recentSearches,
                    onClearRecent = clearRecent,
                )
            }
            listing()
        }
    }

    val target = reportTarget
    if (target != null && reportOptions.isNotEmpty()) {
        // A plain List.map. Lint seems unable to resolve the option type, which lives in the KMP core
        // module, so it falls back to this file's kotlinx.coroutines.flow.map import.
        @SuppressLint("FlowOperatorInvokedInComposition")
        val actions = reportOptions.map { option ->
            MenuAction(
                label = option.text,
                icon = Icons.Filled.Close,
                onSelect = { handleReport(target, option.id) },
            )
        }
        TvMenu(
            title = reportTitle.ifBlank { s.gridReportSponsored },
            subtitle = s.gridReportSponsoredDetail,
            actions = actions,
            onDismiss = {
                reportTarget = null
                reportOptions = emptyList()
            },
        )
    }
}

/**
 * The phone's version of this screen's chrome: a real app bar.
 *
 * Everything lives in the bar: an arrow, the chat's own picture, its name, and the actions as
 * icons. Search expands to fill the bar the way it does on the chat list, so the two screens are
 * searched the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchMediaScaffold(
    chatTitle: String,
    chatPhotoFileId: Int,
    chatMiniThumbnail: ByteArray?,
    isFavorite: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    layout: CardLayout,
    onToggleLayout: () -> Unit,
    onRefresh: () -> Unit,
    /** Offered as chips while the search is open and still empty. */
    recentSearches: List<String> = emptyList(),
    onClearRecent: () -> Unit = {},
    /** Opens with the search already showing, for the screenshot fixture. */
    startSearching: Boolean = false,
    /**
     * The contextual bar shown while videos are being ticked, which takes the whole of the app bar
     * rather than sitting under it: that is what every phone list does when a selection starts, and
     * leaving the ordinary bar in place would leave a back arrow that quietly abandons the picks.
     */
    selectionBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    var searching by rememberSaveable { mutableStateOf(startSearching) }
    val field = remember { FocusRequester() }
    // The bar is a lot of a phone screen to spend on chrome while scrolling, so it leaves on the
    // way down and comes back on the first flick up.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // On a Fire tablet the keyboard is where dictation lives, so the mic button opens it instead.
    val startVoice = rememberVoiceSearch(
        s.gridVoicePromptVideo,
        onKeyboard = {
            runCatching { field.requestFocus() }
            keyboard?.show()
        },
    ) {
        onQuery(it)
        onSubmit()
    }

    // Back leaves the search first and the chat second, which is the order a phone user means it.
    BackHandler(enabled = searching) {
        searching = false
        onQuery("")
        onSubmit()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectionBar != null) selectionBar() else TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    if (searching) {
                        MediaSearchField(
                            query = query,
                            onQueryChange = { onQuery(it); onSubmit() },
                            onVoiceSearch = startVoice,
                            modifier = Modifier.focusRequester(field),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MediaPreview(
                                miniThumbnail = chatMiniThumbnail,
                                thumbnailFileId = chatPhotoFileId,
                                fallbackLabel = chatTitle,
                                modifier = Modifier.size(BAR_AVATAR).clip(CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            M3Text(
                                chatTitle,
                                // Material's default bar title is 22sp, which with an avatar in
                                // front of it leaves room for about eight characters of a name.
                                style = M3MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (searching) {
                                searching = false
                                onQuery("")
                                onSubmit()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        M3Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (searching) s.browseCloseSearch else s.gridBackToChats,
                        )
                    }
                },
                actions = {
                    if (searching) return@TopAppBar
                    IconButton(onClick = { searching = true }) {
                        M3Icon(Icons.Filled.Search, contentDescription = s.gridSearchThisChat)
                    }
                    IconButton(onClick = onToggleFavorite) {
                        M3Icon(
                            if (isFavorite) Icons.Filled.Star else TmIcons.StarOutline,
                            contentDescription = if (isFavorite) {
                                s.browseRemoveFavourite
                            } else {
                                s.browseAddFavourite
                            },
                            // Only the filled star is coloured. The outline is left to the bar's
                            // own action colour, so it sits with the other two icons.
                            tint = if (isFavorite) Tone.accent else LocalContentColor.current,
                        )
                    }
                    // Two icons and a menu, not four icons. A phone app bar has around 200dp to
                    // divide between the title and the actions, and four of them ellipsise the
                    // chat's name on a 1080p panel.
                    BarOverflow(
                        listOf(
                            (if (layout == CardLayout.Grid) s.browseShowAsRows else s.browseShowAsTiles)
                                to onToggleLayout,
                            s.commonRefresh to onRefresh,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching && query.isEmpty() && recentSearches.isNotEmpty()) {
                TouchRecentSearches(
                    searches = recentSearches,
                    onPick = {
                        onQuery(it)
                        onSubmit()
                    },
                    onClear = onClearRecent,
                )
            }
            Box(Modifier.fillMaxSize()) { content() }
        }
    }

    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        // Search is reached from a bar that may be half off the top of the screen by the time it
        // is pressed, and a field nobody can see is a field nobody can type into. Putting the bar
        // back down is part of opening the search, not a separate gesture.
        scrollBehavior.state.heightOffset = 0f
        field.requestFocus()
    }
}

@Composable
private fun MediaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Not a Material SearchBar: that component owns the whole top of the screen, arrow and
    // suggestion sheet included, and this search sits inside an app bar that already has both. It
    // borrows the shape and the colour so it still reads as a search bar.
    Surface(
        shape = M3MaterialTheme.shapes.extraLarge,
        color = Tone.surfaceHigh,
        contentColor = Tone.text,
    ) {
        Row(
            Modifier
                .padding(
                    start = 16.dp,
                    // A trailing icon brings its own room with it; without one the text would run
                    // into the rounded end of the field.
                    end = if (query.isEmpty() && onVoiceSearch == null) 16.dp else 4.dp,
                )
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = M3MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
                cursorBrush = SolidColor(Tone.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        M3Text(
                            s.gridSearchThisChat,
                            style = M3MaterialTheme.typography.bodyLarge,
                            color = Tone.muted,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    M3Icon(Icons.Filled.Close, contentDescription = s.browseClearSearch)
                }
            } else if (onVoiceSearch != null) {
                IconButton(onClick = onVoiceSearch) {
                    M3Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice)
                }
            }
        }
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
    onBack: () -> Unit,
    /** Offered as a row of pills while the remote is in the header and nothing is typed. */
    recentSearches: List<String> = emptyList(),
    onClearRecent: () -> Unit = {},
    /** Shows the recent row whatever has focus, for the screenshot fixture. */
    alwaysShowRecent: Boolean = false,
) {
    val s = LocalStrings.current
    // On Fire TV the voice button opens the keyboard, which the remote's mic dictates into.
    var keyboardRequests by remember { mutableStateOf(0) }
    val startVoice = rememberVoiceSearch(s.gridVoicePromptVideo, onKeyboard = { keyboardRequests++ }) {
        onQuery(it)
        onSubmit()
    }

    Column(Modifier.padding(start = edge, end = edge, top = Tv.SafeV, bottom = 12.dp)) {
        // The same picture the chat was picked by, so it is obvious which one this listing is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The way out, drawn. The remote's Back key works, but Back is the key stick owners
            // are least sure of, so the screen says it as well.
            Pill(s.gridBackToChats, Icons.AutoMirrored.Filled.ArrowBack, showLabel = false, onClick = onBack)
            Spacer(Modifier.width(16.dp))
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
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    s.gridVideosFromChat,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val searchField = remember { FocusRequester() }
        // The recent searches belong to the search, so they are shown only while the remote is up
        // here with it: down in the grid they would cost a row of posters for nothing.
        var headerFocused by remember { mutableStateOf(false) }
        Column(Modifier.onFocusChanged { headerFocused = it.hasFocus }) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {

            TvSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = s.gridSearchThisChat,
                onSubmit = onSubmit,
                editRequests = keyboardRequests,
                modifier = Modifier.weight(1f).focusRequester(searchField),
            )
            if (startVoice != null) {
                // The microphone says what it does, so no label takes room from the search field.
                Pill(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)
            }
            if (query.isNotBlank()) {
                Pill(s.commonClear, Icons.Filled.Close) {
                    // Focus has to leave before the state change, because clearing the query is
                    // what removes this pill from the layout. Letting it vanish while focused
                    // strands the remote: the next press goes nowhere or jumps to the rail.
                    runCatching { searchField.requestFocus() }
                    onQuery("")
                    onSubmit()
                }
            }
            Pill(
                label = if (isFavorite) s.gridRemoveFavouriteShort else s.gridAddFavouriteShort,
                icon = if (isFavorite) Icons.Filled.Star else TmIcons.StarOutline,
                tintWhenIdle = if (isFavorite) Tone.accent else Tone.text,
                onClick = onToggleFavorite,
            )
            // Which arrangement suits a chat depends on the chat: tiles for visual browsing,
            // rows for one that posts long file names. The choice is remembered per screen.
            Pill(
                label = if (layout == CardLayout.Grid) s.browseAsRows else s.browseAsTiles,
                icon = if (layout == CardLayout.Grid) Icons.AutoMirrored.Filled.List else TmIcons.Grid,
                // The two glyphs are the ones every app uses for this, so a label adds nothing.
                showLabel = false,
                onClick = onToggleLayout,
            )
            // Telegram pushes new messages into TDLib's database, but this grid was built from a
            // search that ran when it opened, so a video posted since then needs a fresh search.
            // Icon only, like the refresh on the chat list: a label costs the search field 90dp.
            Pill(s.commonRefresh, Icons.Filled.Refresh, showLabel = false, onClick = onRefresh)
        }
        if (query.isBlank() && recentSearches.isNotEmpty() && (headerFocused || alwaysShowRecent)) {
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.gridRecentSearchesLabel, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                for (recent in recentSearches) {
                    Pill(recent, TmIcons.History) {
                        // The row goes as soon as there is a query, so focus moves first, as Clear does.
                        runCatching { searchField.requestFocus() }
                        onQuery(recent)
                        onSubmit()
                    }
                }
                Pill(s.gridClearRecentSearches, Icons.Filled.Close, showLabel = false) {
                    runCatching { searchField.requestFocus() }
                    onClearRecent()
                }
            }
        }
        }
    }
}

/**
 * The phone's recent searches: a scrolling row of chips under the open search bar, with Clear at
 * the end. Shown only while the field is empty, which is when a chip saves typing.
 */
@Composable
private fun TouchRecentSearches(
    searches: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TOUCH_EDGE, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (recent in searches) {
            SuggestionChip(
                onClick = { onPick(recent) },
                label = { M3Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { M3Icon(TmIcons.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        TextButton(onClick = onClear) { M3Text(s.commonClear) }
    }
}

/**
 * What is on offer while videos are being ticked, on the two devices this screen runs on.
 *
 * A phone gets the contextual app bar it already knows: a cross where the back arrow was, the count
 * where the chat's name was, and the actions on the right. A television gets the same three actions
 * as the pills the heading is built from, because a remote can only reach what can take focus, and
 * an icon-only bar drawn for a thumb is a row of unlabelled squares from a sofa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    /** Null while nothing ticked may be saved, which leaves the bar without a Download. */
    onDownload: (() -> Unit)?,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    edge: Dp,
) {
    val s = LocalStrings.current
    val label = s.gridSelectedCount(count)

    if (isTouch()) {
        TopAppBar(
            title = { M3Text(label, style = M3MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    M3Icon(Icons.Filled.Close, contentDescription = s.gridStopSelecting)
                }
            },
            actions = {
                TextButton(onClick = onSelectAll) { M3Text(s.gridSelectAll) }
                // Words, not a bare arrow: this is the one control on the bar that acts on the
                // ticked videos, and a corner glyph would say nothing about what it starts.
                if (onDownload != null) Button(
                    onClick = onDownload,
                    enabled = count > 0,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    M3Icon(
                        TmIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    M3Text(s.gridDownloadSelected)
                }
            },
        )
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = edge, end = edge, top = Tv.SafeV, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineSmall,
            color = Tone.text,
            modifier = Modifier.weight(1f),
        )
        if (onDownload != null) Pill(s.gridDownload, TmIcons.Download, onClick = onDownload)
        Pill(s.gridSelectAll, Icons.Filled.Check, onClick = onSelectAll)
        Pill(s.commonCancel, Icons.Filled.Close, onClick = onCancel)
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
    val s = LocalStrings.current
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

    val touch = isTouch()
    // The amber edge is the whole point of the treatment: this block is an advertisement and has to
    // stay tellable from the videos around it. On a phone that outline belongs to a card, which
    // draws it in the theme's own amber and surface rather than a panel painted dark by hand.
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (touch) {
                        Modifier
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(Corner.Medium))
                            .background(Tone.surfaceHigh)
                            .border(2.dp, Caution.copy(alpha = 0.8f), RoundedCornerShape(Corner.Medium))
                    },
                )
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
                    modifier = (
                        if (isTouch()) Modifier.weight(TOUCH_ART_SHARE) else Modifier.width(190.dp)
                        )
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Corner.Small))
                        .border(
                            3.dp,
                            if (mediaFocused) Tone.accent else Color.Transparent,
                            RoundedCornerShape(Corner.Small),
                        )
                        .pressable(mediaInteractions, onOpenMedia),
                )
            }

            Column(
                Modifier
                    .weight(if (isTouch()) 1f - TOUCH_ART_SHARE else 1f)
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
                    color = Tone.caution,
                )
                if (item.title.isNotBlank()) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Tone.text,
                    )
                }
                if (item.text.isNotBlank()) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                }
                if (item.additionalInfo.isNotBlank()) {
                    Text(
                        item.additionalInfo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
                if (item.sponsorInfo.isNotBlank()) {
                    Text(
                        item.sponsorInfo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.buttonText.isNotBlank()) {
                    SponsoredButton(item.buttonText, primary = true, onClick = onOpen)
                }
                if (item.canBeReported) {
                    SponsoredButton(s.gridReport, primary = false, onClick = onReport)
                }
            }
        }
    }

    if (touch) {
        OutlinedCard(
            shape = RoundedCornerShape(Corner.Medium),
            border = BorderStroke(2.dp, Tone.caution),
            modifier = Modifier.fillMaxWidth(),
        ) {
            body()
        }
    } else {
        body()
    }
}

/**
 * The call to action and the report affordance on a sponsored block.
 *
 * On a phone these are real buttons, so a screen reader announces them as pressable and the touch
 * target is the full height: reporting an advertisement has to be something a viewer can actually
 * do. The television keeps the hand-drawn one, whose job is to invert when the remote lands on it.
 */
@Composable
private fun SponsoredButton(label: String, primary: Boolean, onClick: () -> Unit) {
    if (isTouch()) {
        if (primary) {
            FilledTonalButton(onClick = onClick) { M3Text(label, maxLines = 1) }
        } else {
            TextButton(onClick = onClick) { M3Text(label, maxLines = 1) }
        }
        return
    }

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Color.White else if (primary) Tone.accent else Tone.surface,
        animationSpec = tween(140),
        label = "sponsoredButton",
    )
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (focused) Color.Black else Tone.text,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .pressable(interactions, onClick)
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
    tintWhenIdle: Color = Tone.text,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surfaceHigh,
        animationSpec = tween(140),
        label = "pill",
    )
    val foreground = if (focused) Tone.onFocusFill else tintWhenIdle

    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .focusRing(focused, CircleShape)
            .pressable(interactions, onClick)
            // Even padding when there are no words, so the pill comes out round rather than as a
            // wide one with a gap in it.
            .padding(horizontal = if (showLabel) 16.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = if (showLabel) null else label, tint = foreground, modifier = Modifier.size(22.dp))
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
        }
    }
}

/**
 * The whole name of whatever the remote is standing on, along the bottom corner.
 *
 * Both arrangements have to cut the name short: a tile has a quarter of the width and a row has
 * two lines of it, and a release name beats either. Like a browser putting the link under the
 * cursor in the corner of the window: out of the way of the listing, and a name too long even for
 * half the screen scrolls past instead of ending in an ellipsis.
 */
@Composable
private fun FullName(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = STRIP_MAX_WIDTH)
            .clip(RoundedCornerShape(Corner.Small))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = Tone.text,
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
 * A media tile that marks focus with a border and a small [focusScale].
 *
 * TV Material's card grows by 10% when focused, and a card in the outermost grid column visibly
 * ran off the screen edge when it did; 5% stays inside the overscan margin.
 */
@Composable
internal fun MediaCard(
    item: MediaItem,
    watched: WatchPoint?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** On the Watched list: a tick on the art and a full bar under it. */
    finished: Boolean = false,
    /** The phone's grid: smaller art, small type, running time over the picture. */
    dense: Boolean = false,
    /** A long press on a phone, or a hold of OK on a remote, which opens the tile's own menu. */
    onLongClick: (() -> Unit)? = null,
    /**
     * Ticked, unticked, or `null` for the ordinary card that is not being picked at all.
     *
     * Three states rather than a boolean, because an unticked tile in a selection still has to say
     * that it can be ticked, and a tile outside one must not carry any such mark.
     */
    selected: Boolean? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(140),
        label = "cardBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }

    if (dense) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Small))
                .selectionEdge(selected, RoundedCornerShape(Corner.Small))
                .longPressable(onClick, onLongClick)
                .padding(DENSE_GAP),
        ) {
            MediaArt(
                item = item,
                watched = watched,
                finished = finished,
                durationOverlay = true,
                compact = true,
                selected = selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(Corner.Small)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = M3MaterialTheme.typography.bodySmall,
                color = Tone.text,
                // Two lines, and always two: a tile that reserves the height whether or not the
                // name needs it keeps the row of pictures beneath it in a straight line.
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            MetaLine(item, watched, compact = true, finished = finished)
        }
        return
    }

    // A phone has no focus to mark, so it takes the surface, elevation and ripple every other card
    // in the system has instead of the hand-painted panel.
    if (isTouch()) {
        Card(
            shape = RoundedCornerShape(Corner.Medium),
            // The same hairline the television's panel carries: in daylight a card's fill is only a
            // shade off the page it sits on.
            border = if (selected == true) {
                BorderStroke(SELECTED_EDGE, Tone.accent)
            } else {
                BorderStroke(1.dp, Tone.outline)
            },
            modifier = modifier.fillMaxWidth().longPressable(onClick, onLongClick),
        ) {
            MediaCardBody(item, watched, selected, finished)
        }
        return
    }

    Column(
        modifier
            .fillMaxWidth()
            .focusScale(focused)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .selectionEdge(selected, RoundedCornerShape(Corner.Medium))
            // A hairline under the focus border, so a tile has an edge even when the remote is
            // somewhere else. In daylight the fill alone is a near-white panel on a near-white
            // page, and the edge is what says where one tile ends.
            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium))
            .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
            .holdOrPress(interactions, onClick, onLongClick),
    ) {
        MediaCardBody(item, watched, selected, finished)
    }
}

/** The picture and the caption under it, which both the phone's card and the TV's panel carry. */
@Composable
private fun MediaCardBody(
    item: MediaItem,
    watched: WatchPoint?,
    selected: Boolean? = null,
    finished: Boolean = false,
) {
    MediaArt(
        item,
        watched,
        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        selected = selected,
        finished = finished,
    )
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            // Two lines, and always two: a release file name fills both, and reserving the height
            // whether or not the name needs it keeps the meta lines across a row level.
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        MetaLine(item, watched, finished = finished)
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
    finished: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** As on the tile: ticked, unticked, or `null` outside a selection entirely. */
    selected: Boolean? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(140),
        label = "rowBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }

    val touch = isTouch()
    val row: @Composable () -> Unit = {
        Row(
            (if (touch) Modifier else modifier)
                .fillMaxWidth()
                .then(
                    if (touch) {
                        Modifier
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(Corner.Medium))
                            .background(if (focused) Tone.surfaceHigh else Tone.surface)
                            .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
                            .selectionEdge(selected, RoundedCornerShape(Corner.Medium))
                            .holdOrPress(interactions, onClick, onLongClick)
                    },
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArt(
                item,
                watched,
                // A fixed 176dp is a fifth of a television but half a phone held upright, which
                // would leave the title about a hundred dp. Reading the whole name is the reason
                // for this arrangement, so on a phone the art takes a share of the row instead.
                (if (touch) Modifier.weight(TOUCH_ART_SHARE) else Modifier.width(ROW_ART_WIDTH))
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(Corner.Small)),
                selected = selected,
                finished = finished,
            )
            Column(
                Modifier.weight(if (touch) 1f - TOUCH_ART_SHARE else 1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    // Two lines here, unlike the tile: this is the arrangement someone picked in
                    // order to read the name, and a row can afford the height a grid cell cannot.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(item, watched, finished = finished)
            }
        }
    }

    // Same reasoning as the tile: on a phone this is a card with the system's own surface and
    // ripple, since the focus border only means anything to a remote.
    if (touch) {
        Card(
            shape = RoundedCornerShape(Corner.Medium),
            border = if (selected == true) BorderStroke(SELECTED_EDGE, Tone.accent) else null,
            modifier = modifier.fillMaxWidth().longPressable(onClick, onLongClick),
        ) {
            row()
        }
    } else {
        row()
    }
}

/**
 * How much room the gesture bar or the navigation buttons take at the bottom of a phone.
 *
 * Read rather than assumed: 0 on a device with hardware keys, about 24 dp under gesture navigation
 * and about 48 dp under three buttons. Without it the last row of a grid sits under the bar.
 */
@Composable
private fun navigationBarPadding(): Dp = with(LocalDensity.current) {
    WindowInsets.navigationBars.getBottom(this).toDp()
}

/** A media preview with its quality tag and saved playback progress. */
@Composable
private fun MediaArt(
    item: MediaItem,
    watched: WatchPoint?,
    modifier: Modifier = Modifier,
    /**
     * The running time over the bottom corner of the picture.
     *
     * Only the captionless phone grid asks for it. Everywhere else the meta line under the tile
     * already carries the duration, and saying it twice a centimetre apart reads as a mistake.
     */
    durationOverlay: Boolean = false,
    /**
     * A tile a third of a phone wide, where the badges have to come down with it: at the
     * television's figures two of them would cover most of the artwork they annotate.
     */
    compact: Boolean = false,
    /**
     * The tick, or the empty ring inviting one. Over the artwork rather than beside the title,
     * because the picture is the part of a tile a viewer is looking at, and on a television it is
     * the only part big enough for a mark to be read from a sofa.
     */
    selected: Boolean? = null,
    /**
     * On the Watched list. The bar runs full width and a tick sits in the bottom corner, the one
     * corner no other badge uses, so a finished video reads as finished at a glance.
     */
    finished: Boolean = false,
) {
    val s = LocalStrings.current
    val tagStyle = if (compact) {
        M3MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val plateH = if (compact) 4.dp else 6.dp
    val plateV = if (compact) 1.dp else 2.dp
    val inset = if (compact) 4.dp else 6.dp
    Box(modifier) {
        // On Home, the show's or the film's own picture over the frame once a match arrives.
        com.tmplayer.ui.online.OnlineArt(item, Modifier.fillMaxSize()) {
            MediaPreview(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val tags = item.qualityTags

        // What the download queue is doing with this video, if anything. Read through
        // [derivedStateOf] so a grid of forty tiles does not all recompose once a second because
        // one of them is counting bytes: only the tile whose own entry changed is invalidated.
        val queue = OfflineDownloads.active.collectAsStateWithLifecycle()
        val coming by remember(item.fileId) {
            androidx.compose.runtime.derivedStateOf { queue.value[item.fileId] }
        }
        val waiting = coming
        if (waiting != null) {
            // Takes the Downloaded or Cached badge's corner, and takes precedence over it: a video being
            // fetched is the more urgent fact, and both in one corner would overlap. Every ticked
            // video says on its own tile where in the queue it is.
            Text(
                when (waiting.stage) {
                    OfflineDownloads.Stage.Running ->
                        waiting.fraction?.let { s.formatter.percent(it.toDouble()) } ?: s.gridStageDownloading
                    OfflineDownloads.Stage.Queued -> s.gridStageQueued
                    OfflineDownloads.Stage.Paused -> s.gridStagePaused
                    OfflineDownloads.Stage.Offline -> s.gridStageWaiting
                    OfflineDownloads.Stage.NoWifi -> s.gridStageNoWifi
                    OfflineDownloads.Stage.Moving -> s.gridStageMoving
                    OfflineDownloads.Stage.Failed -> s.gridStageFailed
                },
                style = tagStyle,
                color = Tone.onAccent,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(inset)
                    .clip(RoundedCornerShape(Corner.ExtraSmall))
                    .background(
                        if (waiting.stage == OfflineDownloads.Stage.Failed) {
                            Tone.danger.copy(alpha = 0.92f)
                        } else {
                            Tone.accent.copy(alpha = 0.92f)
                        },
                    )
                    .padding(horizontal = plateH + 1.dp, vertical = plateV),
            )
        } else if (item.onDevice) {
            Text(
                // Two words for two kinds of copy: one the viewer kept, and one playing left
                // behind that the next play may take.
                if (item.locality == MediaItem.Locality.Downloaded) s.gridBadgeDownloaded else s.gridBadgeCached,
                style = tagStyle,
                // The one badge here that is the app speaking rather than a fact about the picture,
                // so it takes the theme's own colour instead of the plain black plate the tags use.
                color = Tone.onAccent,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(inset)
                    .clip(RoundedCornerShape(Corner.ExtraSmall))
                    .background(Tone.accent.copy(alpha = 0.92f))
                    .padding(horizontal = plateH + 1.dp, vertical = plateV),
            )
        }
        if (tags.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(inset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    Text(
                        tag,
                        style = tagStyle,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corner.ExtraSmall))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = plateH, vertical = plateV),
                    )
                }
            }
        }
        val duration = s.formatter.duration(item.durationSec.toLong())
        if (durationOverlay && duration.isNotEmpty()) {
            Text(
                duration,
                style = if (compact) M3MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(inset)
                    .clip(RoundedCornerShape(Corner.ExtraSmall))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = plateH, vertical = plateV),
            )
        }
        // A finished video draws a full bar even with no saved position: finishing it is exactly
        // what clears the position, and a video marked by hand may never have been opened here.
        // One part way through a second viewing shows where that viewing is instead.
        val fraction = when {
            watched != null && watched.fraction > 0f -> watched.fraction
            finished -> 1f
            else -> 0f
        }
        if (fraction > 0f) {
            // A thin bar along the bottom of the art, where a viewer already looks to see whether
            // they have started something. Material draws it on a phone, gap and rounded ends
            // included, so it matches the bar under the video the tile opens. The television keeps
            // two plain rectangles: read across a room, a gap in a 6dp line looks like a fault in
            // the panel. The track stays black in both, since it lies over artwork rather than
            // over any surface the theme knows about.
            val track = Color.Black.copy(alpha = 0.55f)
            if (isTouch()) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = Tone.accent,
                    trackColor = track,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(Tone.accent),
                    )
                }
            }
        }
        if (finished) {
            WatchedBadge(
                size = if (compact) 18.dp else 26.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Clear of the 6 dp bar along the bottom edge.
                    .padding(start = inset, bottom = inset + 6.dp),
            )
        }
        if (selected != null) {
            val ring = if (compact) 30.dp else 44.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (selected) {
                            Tone.accent.copy(alpha = 0.34f)
                        } else {
                            Color.Black.copy(alpha = 0.28f)
                        },
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(ring)
                    .clip(CircleShape)
                    .background(if (selected) Tone.accent else Color.Black.copy(alpha = 0.55f))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    M3Icon(
                        Icons.Filled.Check,
                        contentDescription = s.gridSelected,
                        tint = Tone.onAccent,
                        modifier = Modifier.size(ring * 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * Click, and on a phone long press, on whatever this is applied to.
 *
 * [combinedClickable] rather than `Card(onClick =)` because a card's own click has nowhere to hang
 * a long press. Where there is no long press to hang, this is a plain clickable and the ripple is
 * the same one either way.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longPressable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) clickable(onClick = onClick)
    else combinedClickable(onClick = onClick, onLongClick = onLongClick)

/**
 * The remote's equivalent: OK opens the video, holding OK opens its menu.
 *
 * A television has no second button, so a hold is the only way to reach the menu that a phone
 * opens with a long press, and that menu is the way into picking several videos at once.
 */
@Composable
private fun Modifier.holdOrPress(
    interactions: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: (() -> Unit)?,
): Modifier =
    if (onHold == null) pressable(interactions, onClick)
    else holdable(interactions, onClick, onHold)

/**
 * The band that says a card has been ticked.
 *
 * Drawn on top of the focus border rather than instead of it, and thicker than it: on a television
 * these two answer different questions at the same time, one being "where is the remote" and the
 * other "what will be downloaded", and a selection read across a room has to survive the remote
 * standing somewhere else entirely.
 */
@Composable
private fun Modifier.selectionEdge(selected: Boolean?, shape: Shape): Modifier =
    if (selected != true) this else border(SELECTED_EDGE, Tone.accent, shape)

/** Running time, file size, and where playback stopped, on the one line both arrangements use. */
@Composable
private fun MetaLine(
    item: MediaItem,
    watched: WatchPoint?,
    compact: Boolean = false,
    finished: Boolean = false,
) {
    val s = LocalStrings.current
    val duration = s.formatter.duration(item.durationSec.toLong())
    val size = s.formatter.size(item.sizeBytes)
    val resume = watched
        ?.takeIf { it.positionMs > 0 }
        ?.let { s.gridStoppedAt(s.formatter.clock(it.positionMs)) }
        ?: s.commonWatched.takeIf { finished }
    Text(
        listOfNotNull(duration.ifEmpty { null }, size.ifEmpty { null }, resume)
            .joinToString("  ·  "),
        style = if (compact) {
            M3MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = if (resume != null) Tone.accent else Tone.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val COLUMNS = 4

/**
 * The narrowest a video tile may be on a phone before the grid drops a column.
 *
 * The art only has to be recognisable, and this width is what leaves room for an extra column and
 * for the name underneath.
 */
private val TOUCH_TILE_MIN = 120.dp

/** No overscan to clear on a phone, so the margin is only what keeps art off the bezel. */
private val TOUCH_EDGE = 16.dp

/** How many rows from the bottom the next page is fetched in the list arrangement. */
private const val LIST_LEAD = 4

private val ROW_ART_WIDTH = 176.dp

/**
 * How much of a list row the art takes on a phone, where a width in dp cannot work: the same row
 * is 360dp upright and twice that on its side. Two fifths leaves a thumbnail big enough to
 * recognise and a title wide enough to read at both.
 */
private const val TOUCH_ART_SHARE = 0.4f

/** The chat's picture in the app bar, at Material's own app-bar avatar size. */
private val BAR_AVATAR = 40.dp

/** Barely a hairline between tiles, so the grid still reads as one sheet of pictures. */
private val DENSE_GAP = 4.dp

/**
 * Thicker than the focus border it is drawn over, so the two never read as the same mark.
 *
 * Kept thin: a heavier edge on every ticked tile turns the grid into a wall of accent and eats
 * enough of each thumbnail to change what the picture shows. This still reads as deliberate
 * against the two dp focus ring, and the tick in the corner is what says "selected" anyway.
 */
private val SELECTED_EDGE = 2.5.dp

/** Half the panel, so the strip never reaches back across the listing it belongs to. */
private val STRIP_MAX_WIDTH = 480.dp

/** How long a pull to refresh keeps its spinner before it accepts that nothing is coming back. */
private const val REFRESH_TIMEOUT_MS = 20_000L

/** How long a gap in focus has to last before the name strip counts it as having left. */
private const val FOCUS_SETTLE_MS = 150L

/**
 * Says how many videos the size limits kept out of this chat, and how many self-destructing ones.
 *
 * At the top rather than the bottom: a remote only scrolls as far as the last focusable card, so a
 * line under the grid would never be seen on a TV. An episode missing with no word about why reads
 * as TMPlayer having lost it, which is what viewers reported.
 */
@Composable
internal fun HiddenVideosNote(bySize: Int, selfDestructing: Int, onShowHidden: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            hiddenVideosText(bySize, selfDestructing),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Lifts the limits for this chat only, for as long as it is open; Settings keeps them.
        // A self-destructing video has nothing to show here, so only the size count earns it.
        if (bySize > 0) {
            TmSecondaryButton(onClick = onShowHidden) { Text(s.commonShowHidden) }
        }
    }
}

internal fun hiddenBySizeText(count: Int): String = L.browseHiddenBySize(count)

/**
 * A self-destructing video is gone once it has been opened, which only Telegram itself can honour,
 * so the listing leaves it out and says where it can be watched instead.
 */
internal fun hiddenSelfDestructingText(count: Int): String =
    L.gridSelfDestructingHidden(count)

/** Both sentences, each only when it has something to count. */
internal fun hiddenVideosText(bySize: Int, selfDestructing: Int): String =
    listOfNotNull(
        hiddenBySizeText(bySize).takeIf { bySize > 0 },
        hiddenSelfDestructingText(selfDestructing).takeIf { selfDestructing > 0 },
    ).joinToString(" ")
