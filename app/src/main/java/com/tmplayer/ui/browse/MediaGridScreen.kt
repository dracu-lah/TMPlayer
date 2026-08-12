package com.tmplayer.ui.browse

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
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
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressable
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A store that lives exactly as long as one media screen.
 *
 * The alternative was the activity's own store, which lives as long as the app and never forgets
 * anything put into it.
 */
private class MediaScreenStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Suppress("UNCHECKED_CAST")
private class MediaListViewModelFactory(
    private val chatId: Long,
    private val minSize: Long,
    private val maxSize: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MediaListViewModel(chatId, minSize, maxSize) as T
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
    val context = LocalContext.current
    // A phone has neither overscan to clear nor a fixed width to plan a grid against, so both of
    // those figures are asked for again below rather than assumed.
    val touch = !FormFactor.isTv(context)
    val edge = if (touch) TOUCH_EDGE else Tv.SafeH
    // The limits are part of the key: changing them in Settings has to rebuild the listing,
    // not leave a stale one filtered by the old bounds.
    //
    // Scoped to this screen rather than to the activity. Against the activity's store nothing was
    // ever evicted, so every chat visited in a session left behind a whole item list, each of them
    // holding a few hundred minithumbnail byte arrays, and every change to the size limits added
    // another copy of the same chat beside it. On a 1 GB stick that is the likeliest way this app
    // gets killed, and the cost of the change is one re-listing when a chat is reopened.
    val owner = remember(chatId, minSizeBytes, maxSizeBytes) { MediaScreenStore() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val viewModel: MediaListViewModel = viewModel(
        viewModelStoreOwner = owner,
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

    val refresh = {
        viewModel.load()
        if (offline) onOfflineAction("You're offline. Showing saved videos.")
    }

    // A pull has to hold the spinner until the listing has actually come back, and the listing
    // reports nothing of the sort: a refresh that keeps its content leaves the state a Content the
    // whole way through. What does change is the instance, once the new page lands, so that is what
    // the gesture waits on. The timeout is there because a request against a dead connection never
    // emits anything at all, and a spinner left turning for ever is worse than one that gives up.
    // Whichever video a long press is asking about, and nothing while none is.
    var showingDetailsOf by remember(chatId) { mutableStateOf<MediaItem?>(null) }

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

            // The phone's grid is compact but not captionless: smaller art than a television's
            // card, two lines of the file name under it in small type, and a hairline of a gap.
            // A sheet of unlabelled pictures reads well for a camera roll and badly for a chat
            // full of releases, where the name is the only thing telling two of them apart.
            val dense = touch && layout == CardLayout.Grid
            val gap = if (dense) DENSE_GAP else 16.dp
            val padding = PaddingValues(
                start = if (dense) DENSE_GAP else edge,
                end = if (dense) DENSE_GAP else edge,
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
                                        dense = dense,
                                        onClick = { onPlay(item) },
                                        onLongClick = if (touch) {
                                            { showingDetailsOf = item }
                                        } else {
                                            null
                                        },
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
                                        onLongClick = if (touch) {
                                            { showingDetailsOf = item }
                                        } else {
                                            null
                                        },
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

                showingDetailsOf?.let { item ->
                    MediaActionsSheet(
                        item = item,
                        chatTitle = chatTitle,
                        watched = watchProgress[
                            SettingsStore.progressKey(item.chatId, item.messageId),
                        ],
                        onPlay = { onPlay(item) },
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
                                // where it only floated the chip well clear of the bottom edge
                                // for no reason. The gesture bar is the real obstacle there.
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
                                "Loading more…",
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
            content = {
                // The circular arrow stays in the overflow, because a listing that is empty or in
                // an error state has nothing to drag, but a downward pull is what a phone user
                // reaches for first and this screen ignored it entirely.
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
            )
            listing()
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

/**
 * The phone's version of this screen's chrome: a real app bar.
 *
 * What it replaces had no back affordance at all (only the hardware key knew how to leave), a 32sp
 * title padded by the television's overscan constant so that it sat under the status bar, and a row
 * of five labelled pills underneath it. That is most of a phone screen spent before the first video.
 *
 * Everything moves into the bar: an arrow, the chat's own picture, its name, and the actions as
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
    content: @Composable () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    // The bar carries a picture, a name and three actions, which is a lot of a phone screen to
    // spend on chrome while somebody is scrolling through videos. It leaves on the way down and
    // comes back on the first flick up, the way every Google app's list screen behaves.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val startVoice = rememberVoiceSearch("Say a video name") {
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
            TopAppBar(
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
                                // front of it left room for about eight characters of a chat name.
                                // Telegram's own chat bar is around this size for the same reason.
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
                            contentDescription = if (searching) "Close search" else "Back to chats",
                        )
                    }
                },
                actions = {
                    if (searching) return@TopAppBar
                    IconButton(onClick = { searching = true }) {
                        M3Icon(Icons.Filled.Search, contentDescription = "Search this chat")
                    }
                    IconButton(onClick = onToggleFavorite) {
                        M3Icon(
                            if (isFavorite) Icons.Filled.Star else TmIcons.StarOutline,
                            contentDescription = if (isFavorite) {
                                "Remove from favourites"
                            } else {
                                "Add to favourites"
                            },
                            // Only the filled star is coloured. The outline is left to the bar's
                            // own action colour, so it sits with the other two icons.
                            tint = if (isFavorite) Tone.accent else LocalContentColor.current,
                        )
                    }
                    // Two icons and a menu, not four icons. A phone app bar has around 200dp to
                    // divide between the title and the actions, and with four of them the chat's
                    // name was cut to "Weeke..." on a 1080p panel.
                    BarOverflow(
                        listOf(
                            (if (layout == CardLayout.Grid) "Show as rows" else "Show as tiles")
                                to onToggleLayout,
                            "Refresh" to onRefresh,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
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
    // Not a Material SearchBar: that component owns the whole top of the screen, including its own
    // back arrow and its own suggestion sheet, and this search lives inside an app bar that already
    // has an arrow, an avatar and an overflow. What it borrows instead is the shape and the colour,
    // so the field reads as the same thing the search bar would have been.
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
                            "Search this chat",
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
                    M3Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            } else if (onVoiceSearch != null) {
                IconButton(onClick = onVoiceSearch) {
                    M3Icon(TmIcons.Mic, contentDescription = "Search by voice")
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
            // Icon only, like the refresh on the chat list: the circular arrow is the one glyph
            // nobody has to be told the meaning of, and the word cost the search field 90dp.
            Pill("Refresh", Icons.Filled.Refresh, showLabel = false, onClick = onRefresh)
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

    val touch = isTouch()
    // The amber edge is the whole point of the treatment: this block is an advertisement and has to
    // stay tellable from the videos around it. On a phone that outline belongs to a card, which
    // draws it in the theme's own amber and against the theme's own surface, rather than to a
    // hand-painted panel that would still be dark grey on a light screen.
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
                            .background(SurfaceRaised)
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
                            if (mediaFocused) Accent else Color.Transparent,
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
                    SponsoredButton("Report", primary = false, onClick = onReport)
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
 * On a phone these are real buttons. What they were was a [Text] with a rounded background behind
 * it, which a screen reader announced as a label rather than as something pressable, and whose
 * touch target came out around 40dp tall. Reporting an advertisement is the one thing on this
 * screen a viewer has a right to be able to do, so it gets a component that behaves like a button.
 * The television keeps the hand-drawn one, whose whole job is to invert when the remote lands on it.
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
        targetValue = if (focused) Color.White else if (primary) Accent else SurfaceDark,
        animationSpec = tween(140),
        label = "sponsoredButton",
    )
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (focused) Color.Black else TextPrimary,
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
            .clip(CircleShape)
            .background(background)
            .pressable(interactions, onClick)
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
            .clip(RoundedCornerShape(Corner.Small))
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
    /** The phone's grid: smaller art, small type, running time over the picture. */
    dense: Boolean = false,
    /** A long press on a phone, which shows the whole name a tile had to cut. */
    onLongClick: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(140),
        label = "cardBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }

    if (dense) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Small))
                .longPressable(onClick, onLongClick)
                .padding(DENSE_GAP),
        ) {
            MediaArt(
                item = item,
                watched = watched,
                durationOverlay = true,
                compact = true,
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
            MetaLine(item, watched, compact = true)
        }
        return
    }

    // A phone has no focus to mark, so the hand-painted panel and its border buy it nothing: what
    // it wants is the surface, the elevation and the ripple every other card in the system has.
    if (isTouch()) {
        Card(
            shape = RoundedCornerShape(Corner.Medium),
            modifier = modifier.fillMaxWidth().longPressable(onClick, onLongClick),
        ) {
            MediaCardBody(item, watched)
        }
        return
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(Corner.Medium))
            .pressable(interactions, onClick),
    ) {
        MediaCardBody(item, watched)
    }
}

/** The picture and the caption under it, which both the phone's card and the TV's panel carry. */
@Composable
private fun MediaCardBody(item: MediaItem, watched: WatchPoint?) {
    MediaArt(item, watched, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            // Two lines. A release file name fills both and then some, and one line cut a title
            // so early that two videos in the same chat were often the same four words.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        MetaLine(item, watched)
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
    onLongClick: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
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
                            .background(if (focused) SurfaceRaised else SurfaceDark)
                            .border(3.dp, border, RoundedCornerShape(Corner.Medium))
                            .pressable(interactions, onClick)
                    },
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArt(
                item,
                watched,
                // A fixed 176dp is a fifth of a television and half a phone held upright, where it
                // would leave the title about a hundred dp to live in. Reading the whole name is
                // the reason somebody chose this arrangement, so on a phone the art takes a share
                // of the row instead and the words keep the rest.
                (if (touch) Modifier.weight(TOUCH_ART_SHARE) else Modifier.width(ROW_ART_WIDTH))
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(Corner.Small)),
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
                MetaLine(item, watched)
            }
        }
    }

    // Same reasoning as the tile: on a phone this is a card, with the system's own surface and
    // ripple, and the border only ever meant anything to a remote.
    if (touch) {
        Card(
            shape = RoundedCornerShape(Corner.Medium),
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
 * Read rather than assumed: it is 0 on a device with hardware keys, about 24 dp under gesture
 * navigation and about 48 dp under three buttons, and the last row of a grid was drawn under all
 * three of them.
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
     * A tile a third of a phone wide, where the badges have to come down with it. At the
     * television's size they are a label in the corner of a picture; at this size, drawn to the
     * same figures, two of them cover most of the artwork they are annotating.
     */
    compact: Boolean = false,
) {
    val tagStyle = if (compact) {
        M3MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val plateH = if (compact) 4.dp else 6.dp
    val plateV = if (compact) 1.dp else 2.dp
    val inset = if (compact) 4.dp else 6.dp
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
                // Not "On this TV": the same badge is drawn on a phone, where it was telling the
                // viewer about a television they are not holding.
                "Saved",
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
        val duration = MediaMapper.formatDuration(item.durationSec)
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
        if (watched != null && watched.fraction > 0f) {
            // A thin bar along the bottom of the art, the one place a viewer already looks
            // to see whether they have started something.
            //
            // Material draws this one on a phone, gap and rounded ends included, so it matches the
            // bar under the video the tile opens. The television keeps the two plain rectangles:
            // its progress bar is read across a room, where a gap in the middle of a 6dp line only
            // reads as a fault in the panel. The track stays black in both, because it lies over
            // artwork rather than over any surface the theme knows about.
            val track = Color.Black.copy(alpha = 0.55f)
            if (isTouch()) {
                LinearProgressIndicator(
                    progress = { watched.fraction },
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
                            .fillMaxWidth(watched.fraction)
                            .fillMaxHeight()
                            .background(Accent),
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
 * What can be done with the video being held down.
 *
 * A television has the name strip along the bottom, which follows the remote with no gesture to
 * learn. A phone has nothing equivalent, so the long press opened a sheet, and for a while that
 * sheet said the name and the numbers and offered nothing at all to do about them.
 *
 * Sharing and handing the file to another player are only offered once the whole video is on the
 * device, because both of them pass a path to somebody else's app: a half-downloaded file opened
 * in VLC is a video that plays for two minutes and stops, with nothing on screen to say why.
 */
@Composable
private fun MediaActionsSheet(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by OfflineDownloads.active.collectAsStateWithLifecycle()
    val downloading = downloads[item.fileId]

    // Asked once, when the sheet opens: whether the file is already here decides half the menu.
    val onDisk by produceState(initialValue = false, item.fileId, downloading) {
        value = runCatching { Td.isFileCached(item.fileId) }.getOrDefault(false)
    }

    val resume = watched
        ?.takeIf { it.positionMs > 0 }
        ?.let { "  ·  Stopped at ${com.tmplayer.player.StreamStats.formatClock(it.positionMs)}" }
        .orEmpty()

    val actions = buildList {
        add(
            MenuAction(
                label = if (watched != null && watched.positionMs > 0) "Resume" else "Play",
                icon = Icons.Filled.PlayArrow,
                onSelect = { onDismiss(); onPlay() },
            ),
        )
        when {
            downloading != null -> add(
                MenuAction(
                    label = "Stop the download",
                    icon = Icons.Filled.Close,
                    detail = downloading.fraction
                        ?.let { "${(it * 100).toInt()}% so far. What arrived is kept." },
                    onSelect = {
                        OfflineDownloads.cancel(context, item.fileId)
                        onDismiss()
                    },
                ),
            )
            onDisk -> add(
                MenuAction(
                    label = "Downloaded",
                    icon = TmIcons.Download,
                    detail = "On this phone already. It plays without a connection.",
                    onSelect = { onDismiss() },
                ),
            )
            else -> add(
                MenuAction(
                    label = "Download for later",
                    icon = TmIcons.Download,
                    detail = "Keeps going with the app closed, so it is there without a signal",
                    onSelect = {
                        // Android 13 counts a download's progress notification as one the viewer
                        // has to have agreed to. Asked here rather than at first launch, because
                        // here is the one moment the request explains itself.
                        askForNotifications(context)
                        OfflineDownloads.start(context, item, chatTitle)
                        onDismiss()
                    },
                ),
            )
        }
        if (onDisk) {
            add(
                MenuAction(
                    label = "Share",
                    icon = Icons.Filled.Share,
                    onSelect = {
                        scope.launch { shareVideo(context, item, send = true) }
                        onDismiss()
                    },
                ),
            )
            add(
                MenuAction(
                    label = "Open in another player",
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    onSelect = {
                        scope.launch { shareVideo(context, item, send = false) }
                        onDismiss()
                    },
                ),
            )
        }
    }

    TvMenu(
        title = item.title,
        subtitle = MediaMapper.formatDuration(item.durationSec) + "  ·  " +
            MediaMapper.formatSize(item.sizeBytes) + resume,
        actions = actions,
        onDismiss = onDismiss,
    )
}

/**
 * Asks for the notification permission, once, on the versions that have one.
 *
 * A refusal is not treated as a failure: the download still runs, it simply runs without a bar to
 * watch. Nothing here waits on the answer, because the fetch is the thing the viewer pressed.
 */
private fun askForNotifications(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val activity = context.findActivity() ?: return
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        NOTIFICATION_REQUEST,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val NOTIFICATION_REQUEST = 7301

/**
 * Hands the downloaded file to another app, either to send or to play.
 *
 * Through a `FileProvider`, because TDLib keeps its files inside this app's own storage and a
 * `file://` path handed across a process boundary is a `FileUriExposedException` on every Android
 * this app runs on. The grant is read-only and lasts as long as the other app's task.
 */
private suspend fun shareVideo(context: Context, item: MediaItem, send: Boolean) {
    val path = Td.localFilePath(item.fileId)
    if (path.isNullOrBlank()) {
        // Not a state the menu should be able to reach, since these two entries only appear for a
        // file already on the phone. Said out loud anyway: silence here is indistinguishable from
        // a press that missed.
        Log.w(SHARE_TAG, "No local file for ${item.fileId}")
        Toast.makeText(context, "That video is not on this phone yet.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
    }.onFailure { Log.w(SHARE_TAG, "Cannot share $path", it) }.getOrNull() ?: run {
        Toast.makeText(context, "That video cannot be handed to another app.", Toast.LENGTH_SHORT)
            .show()
        return
    }

    val mime = item.mimeType.ifBlank { "video/*" }
    val intent = if (send) {
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, item.title)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // The chooser is deliberate on both: ACTION_VIEW without one lands in whatever app once won
    // the default, which for a video on most phones is this one, and that is a loop.
    val chooser = Intent.createChooser(intent, if (send) "Share" else "Open with")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure {
        Log.w(SHARE_TAG, "No app took the video", it)
        Toast.makeText(context, "Nothing on this phone opens that.", Toast.LENGTH_SHORT).show()
    }
}

private const val SHARE_TAG = "ShareVideo"

/** Running time, file size, and where playback stopped, on the one line both arrangements use. */
@Composable
private fun MetaLine(item: MediaItem, watched: WatchPoint?, compact: Boolean = false) {
    val duration = MediaMapper.formatDuration(item.durationSec)
    val size = MediaMapper.formatSize(item.sizeBytes)
    val resume = watched
        ?.takeIf { it.positionMs > 0 }
        ?.let { "Stopped at ${com.tmplayer.player.StreamStats.formatClock(it.positionMs)}" }
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
 * Smaller than it was: the art only has to be recognisable, and taking 36dp off it is what buys
 * the extra column and the room for the name underneath.
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

/** Half the panel, so the strip never reaches back across the listing it belongs to. */
private val STRIP_MAX_WIDTH = 480.dp

/** How long a pull to refresh keeps its spinner before it accepts that nothing is coming back. */
private const val REFRESH_TIMEOUT_MS = 20_000L

/** How long a gap in focus has to last before the name strip counts it as having left. */
private const val FOCUS_SETTLE_MS = 150L
