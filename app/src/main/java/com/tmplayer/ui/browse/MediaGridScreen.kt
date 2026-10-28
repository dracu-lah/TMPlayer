package com.tmplayer.ui.browse

import com.tmplayer.ui.components.keepTopInSight
import android.Manifest
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.tmplayer.ui.components.TouchPullRefresh
import com.tmplayer.ui.components.secondaryPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.components.FloatingBackButton
import com.tmplayer.ui.components.FloatingBarIcon
import com.tmplayer.ui.components.PopoverAnchor
import com.tmplayer.ui.components.StarIcon
import com.tmplayer.ui.components.rememberPopoverAnchor
import com.tmplayer.ui.components.FloatingChrome
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.shadow
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.StickyActionBar
import com.tmplayer.ui.components.rememberCompactChrome
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaFeedEntry
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesProgress
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
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.SlowAnswerNotice
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressable
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.ProgressLineInset
import com.tmplayer.ui.components.TileBadge
import com.tmplayer.ui.components.TileBadgeFill
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.components.TileGrid
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.components.PosterArt
import com.tmplayer.data.MediaName
import androidx.compose.ui.text.font.FontWeight

/** A store that lives exactly as long as one media screen, so its view models are released with it. */
private class MediaScreenStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The shelves last laid out for the few chats opened most recently, with what each was laid out
 * from. A chat opened again from the page cache ([com.tmplayer.data.PageQueries]) usually lists
 * the very same videos, and then its first frame is the shelf itself, not the skeleton. Touched on
 * the main thread only.
 */
private object ShelfMemo {
    private const val CHATS = 6
    private var kept = LinkedHashMap<Long, Pair<List<Any?>, Shelved>>()

    fun match(chatId: Long, inputs: List<Any?>): Shelved? = kept[chatId]?.takeIf { it.first == inputs }?.second

    fun keep(chatId: Long, inputs: List<Any?>, shelf: Shelved) {
        val next = LinkedHashMap(kept)
        next.remove(chatId)
        next[chatId] = inputs to shelf
        while (next.size > CHATS) next.remove(next.keys.first())
        kept = next
    }
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
            downloadedIds = { LocalDownloads.presentIds(settings) },
            topicMemory = settings.topicMemory(),
        ) as T
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
    /**
     * When each video was last watched, keyed like [watchProgress] (see
     * [com.tmplayer.data.NewEpisodes.touchTimes]), for the "New" badge on a show's tile.
     */
    touchTimes: Map<String, Long> = emptyMap(),
    /** "Mark as watched" (true) or "Mark as unwatched" (false) from a tile's menu. */
    onSetWatched: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onToggleFavorite: () -> Unit,
    /** Leaving the chat. On a phone this is the app bar's arrow as well as the hardware key. */
    onBack: () -> Unit = {},
    onPlay: (MediaItem) -> Unit,
    telegramConnected: Boolean,
    offline: Boolean,
    onOfflineAction: (String) -> Unit,
    connectionNotice: ConnectionNotice,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    // A phone has neither overscan to clear nor a fixed width to plan a grid against, so both of
    // those figures are asked for again below rather than assumed.
    val touch = !FormFactor.isTv(context)
    val lessMotion = rememberReducedMotion() || Motion.reduced
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
    val topicBar by viewModel.topics.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    val recentStore = remember(context) { SettingsStore(context) }
    val recentSearches by recentStore.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentScope = rememberCoroutineScope()
    val clearRecent: () -> Unit = { recentScope.launch { recentStore.clearRecentSearches() } }
    // This chat's order (see VideoSort), and the viewer's corrections, which regroup and rename.
    val sort by remember(chatId) { recentStore.videoSort(chatId) }.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.VideoSort.DEFAULT)
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsStateWithLifecycle()
    var sorting by remember { mutableStateOf(false) }
    // Which of the chat's videos are listed (see VideoFilter), remembered per chat beside its order.
    val filter by remember(chatId) { recentStore.videoFilter(chatId) }.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.VideoFilter.DEFAULT)
    val clearFilter: () -> Unit = { recentScope.launch { recentStore.setVideoFilter(chatId, com.tmplayer.data.VideoFilter.All) } }
    // One menu for the order and the Show filter, the same groups on every device. The size limits
    // have their own "n hidden" button (sizeHidden below) and the full pair is in Settings.
    val menu = VideoMenuState(
        sort = sort,
        filter = filter,
        onSort = { picked ->
            sorting = false
            if (picked != sort) recentScope.launch { recentStore.setVideoSort(chatId, picked) }
        },
        onFilter = { picked ->
            sorting = false
            if (picked != filter) recentScope.launch { recentStore.setVideoFilter(chatId, picked) }
        },
    )
    // Videos outside the size limits, said by an "n hidden" button for as long as the chat is open
    // and changed from there (SizeQuickSheet). A search ignores the limits, so it hides nothing.
    val sizeHidden by viewModel.sizeHidden.collectAsStateWithLifecycle()
    val shownHidden = if (query.isBlank()) sizeHidden else 0
    var sizePicking by remember { mutableStateOf(false) }
    if (sizePicking) {
        SizeQuickSheet(
            current = minSizeBytes,
            max = maxSizeBytes,
            hidden = shownHidden,
            onPick = { picked ->
                sizePicking = false
                if (picked != minSizeBytes) recentScope.launch { runCatching { recentStore.setMinSizeBytes(picked) } }
            },
            onDismiss = { sizePicking = false },
        )
    }
    // A search for the developer phrase turns on the hidden name check in Settings instead.
    val submit: () -> Unit = {
        if (query.trim().equals(com.tmplayer.data.DEVELOPER_PHRASE, ignoreCase = true)) {
            recentScope.launch { recentStore.setDeveloperOptions(true) }
            com.tmplayer.ui.components.showMessage(context, s.logsDeveloperOn)
            query = ""
        } else {
            viewModel.search(query)
        }
    }
    // Typing searches the chat once it rests (MediaListViewModel.type); the search key, a recent
    // search, voice and Clear go through submit, at once.
    LaunchedEffect(query) { viewModel.type(query) }
    val chatSearching by viewModel.searching.collectAsStateWithLifecycle()
    // A phone's Sort is a popover over the chat's bar, drawn by TouchMediaScaffold; a remote's is this panel.
    if (sorting && !isTouch()) {
        VideoSortSheet(menu, onDismiss = { sorting = false })
    }
    // Whatever the remote is standing on, for the name strip along the bottom.
    var standingOn by remember { mutableStateOf<MediaItem?>(null) }
    // It changes on every move of the remote, so the grid's own Box reads only whether there is
    // one (for the chips' clearance) and the strip reads which one in its own scope below: a
    // read here recomposed the whole grid on every step.
    val standing by remember { derivedStateOf { standingOn != null } }
    // The states every tile reads, collected once for the grid ([LocalTileShared]).
    val tileShared = com.tmplayer.ui.components.rememberTileShared()
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

    // The video whose page is open (nothing while none is), over the pages "More like this" was taken from, so Back
    // walks back through them before it closes the page.
    val details = rememberDetailBackStack<MediaItem>(chatId, itemOf = { it }, rebuild = { item, _ -> item })
    // The listing as last arranged, shows folded together: where an open show is looked up again
    // after the phone is turned and the activity made anew.
    var shelfShown by remember(chatId) { mutableStateOf<List<ShelfEntry>>(emptyList()) }
    /** The show whose seasons and episodes are open over the grid, kept across a turn of the phone. */
    val openSeries = rememberOpenSeries(chatId) { key, chat ->
        shelfShown.firstNotNullOfOrNull { entry -> (entry as? ShelfEntry.Show)?.series?.takeIf { it.key == key && it.cover.chatId == chat } }
    }
    // Whichever video a long press is asking about: the quick menu, which leads to the page.
    var heldMenuOf by remember(chatId) { mutableStateOf<MediaItem?>(null) }
    // A show card held: its menu (HX20).
    var heldSeries by remember(chatId) { mutableStateOf<com.tmplayer.data.Series?>(null) }

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
    val detailFirst by settings.detailFirst.collectAsState(initial = true)
    val watch = remember(watchProgress, watchedVideos, touchTimes) {
        SeriesWatch(
            point = { watchProgress[SettingsStore.progressKey(it.chatId, it.messageId)] },
            finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedVideos },
            touchedAt = { touchTimes[SettingsStore.progressKey(it.chatId, it.messageId)] },
        )
    }

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

    // The television's search field is being typed into. Each pause in the typing lists the
    // answer, and nothing the listing does then may take the field's focus: that closed the
    // keyboard after a letter or two (user, 2026-10-23).
    var typing by remember { mutableStateOf(false) }
    val typingNow = typing && !selecting

    val listing: @Composable () -> Unit = {
      CompositionLocalProvider(com.tmplayer.ui.components.LocalTileShared provides tileShared) {
        StateScaffold(
            state,
            onRetry = viewModel::load,
            loading = { MediaGridSkeleton() },
            takeFocus = !typingNow,
            onAction = { action ->
                viewModel.act(action)
                // The search across every chat lives with the chat list: back there, where it opens.
                if (action == com.tmplayer.ui.components.StateAction.SearchAllChats) onBack()
            },
        ) { list ->
            // On a phone the column count follows the width, which follows the orientation.
            // Everything that counts in columns, the paging lead included, reads it from here so
            // there is only ever one figure in play.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            // Films and shows are 2:3 posters, every other video a 16:9 card, and the two counts
            // across differ; the grid's own columns are a count both divide (see TileGrid).
            val landscape = maxWidth > maxHeight
            val posterColumns = TileGrid.posterColumns(maxWidth - edge * 2, touch, landscape)
            val wideColumns = TileGrid.wideColumns(maxWidth - edge * 2, touch, landscape)
            val columns = TileGrid.columns(posterColumns, wideColumns)
            // A show is one tile that opens its seasons and episodes, and a film posted several
            // times over (720p, 1080p, HEVC) is one tile led by the copy the viewer already
            // started, the rest a pick in its details. Not while searching or ticking videos:
            // then every episode stands on its own, to be found or picked one by one.
            val grouping = query.isBlank() && !selecting
            // Worked out off the main thread: grouping reads every file name through the name
            // parser, which on a page of a few hundred videos is a frame or two on a television
            // stick. The skeleton stays up until the first answer, so tiles never jump from one
            // file each into shows; later pages keep the previous listing up while they are read.
            // Sorted only outside a search, whose order is its ranking.
            val order = if (query.isBlank()) sort else com.tmplayer.data.VideoSort.Newest
            // Filtered too, outside a search and outside ticking, where every video has to be reachable.
            val shownFilter = if (grouping) filter else com.tmplayer.data.VideoFilter.All
            // A chat opened again from the page cache lists what it listed last time: the shelf laid
            // out from those same videos then is used for the first frame, rather than a frame or
            // two of skeleton while it is worked out again.
            val shelfInputs = listOf(list.items, watchProgress, grouping, order, corrections, watchedVideos, shownFilter)
            val shelfBefore = remember(chatId) { ShelfMemo.match(chatId, shelfInputs) }
            val arrangedShelf by produceState(shelfBefore, list.items, watchProgress, grouping, order, corrections, watchedVideos, shownFilter) {
                value = withContext(Dispatchers.Default) {
                    // Each tile's online question read here, off the main thread, where the tiles
                    // would otherwise each read it as they scroll in ([MediaItem.metaQuery]).
                    if (com.tmplayer.online.OnlineMetadata.current != null) list.items.forEach { it.metaQuery }
                    arrangeShelf(list.items, watchProgress, grouping, order, watchedVideos.keys, corrections, shownFilter)
                }.also { ShelfMemo.keep(chatId, shelfInputs, it) }
            }
            val (shelf, repeatedCaptions, posterKeys) = arrangedShelf ?: run {
                MediaGridSkeleton()
                return@BoxWithConstraints
            }
            val feed = remember(shelf, list.sponsored) {
                placeSponsored(shelf, list.sponsored)
            }
            // A chat or a topic that is one course of lessons says how far through it the viewer
            // is, over the listing; not while searching or ticking, when the listing is a pick.
            // Off the main thread, as the shelf is: it reads every name through the lesson parser,
            // and runs again whenever anything is watched.
            val course by produceState<com.tmplayer.data.CourseProgress?>(null, list.items, watch, grouping) {
                value = if (grouping) withContext(Dispatchers.Default) { watch.course(list.items) } else null
            }
            val courseTitle = topicBar?.let { bar -> bar.nameOf(bar.selected) } ?: chatTitle
            // Every order but Newest is of what has loaded so far, while older pages remain.
            val loadedOnly = com.tmplayer.data.VideoSorting.sortsLoadedOnly(order, list.endReached)
            // A filter in force says so over the listing, with the way out, so a chat that seems to
            // have lost half its videos never reads as TMPlayer losing them.
            val filterNote = shownFilter != com.tmplayer.data.VideoFilter.All
            val filterTotal = arrangedShelf?.total ?: 0
            // Self-destructing videos are left out (only Telegram opens them): a quiet line says so.
            val selfDestructing by viewModel.selfDestructing.collectAsStateWithLifecycle()
            val selfDestructNote = query.isBlank() && selfDestructing > 0
            val courseHeader: @Composable () -> Unit = {
                if (filterNote) {
                    FilterNote(
                        label = videoFilterLabel(shownFilter, s),
                        shown = shelf.size,
                        total = filterTotal,
                        onClear = clearFilter,
                        modifier = Modifier.padding(horizontal = if (touch) 4.dp else 0.dp).padding(bottom = 6.dp),
                    )
                }
                if (loadedOnly) {
                    Text(
                        s.gridSortLoadedNote(list.items.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.muted,
                        modifier = Modifier.padding(horizontal = if (touch) 4.dp else 0.dp).padding(bottom = 6.dp),
                    )
                }
                if (selfDestructNote) {
                    Text(
                        s.gridSelfDestructingHidden(selfDestructing),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.muted,
                        modifier = Modifier.padding(horizontal = if (touch) 4.dp else 0.dp).padding(bottom = 6.dp),
                    )
                }
                course?.let {
                    CourseHeader(
                        progress = it,
                        title = courseTitle,
                        onPlay = onPlay,
                        modifier = Modifier.padding(horizontal = if (touch) 4.dp else 0.dp).padding(bottom = 4.dp),
                    )
                }
            }
            LaunchedEffect(list.items) {
                listedItems = list.items
                com.tmplayer.data.NameReport.remember(chatId, list.items)
            }
            LaunchedEffect(shelf) { shelfShown = shelf }
            // Online metadata is on by default, so the first grid says once where posters come
            // from (see OnlineMetaNotice); not while searching or ticking, when the listing is a pick.
            val metaNotice = grouping && metaNoticeShown()
            // Where this listing was left on the last visit, so coming back to a chat finds the
            // same pages at the same place ([MediaListViewModel.restoredScroll]); written as it goes.
            val restored = remember(viewModel) { viewModel.restoredScroll }
            val gridState = com.tmplayer.ui.components.rememberAheadGridState(restored.first, restored.second)
            DisposableEffect(viewModel) {
                onDispose { viewModel.rememberScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            }
            val firstItem = remember { FocusRequester() }
            // The tile a video's page (or a show's, or the hold menu) was opened from, which the
            // remote goes back to when it closes rather than to the first tile.
            val tileReturn = rememberTileReturn()
            val firstKey = shelf.firstOrNull()?.key
            // The first row of a television's listing, by key. Focus landing there asks to be
            // brought into view with the focus clearance above it, which scrolls the listing a
            // little way off the top. Back to the very top instead, once that scroll has settled.
            val firstRowKeys = remember(feed, posterColumns, wideColumns) {
                if (touch) {
                    emptySet()
                } else {
                    val across = if (firstKey in posterKeys) posterColumns else wideColumns
                    feed.takeWhile { it is MediaFeedEntry.Media }
                        .take(across)
                        .map { (it as MediaFeedEntry.Media).item.key }
                        .toSet()
                }
            }
            val metaNoticeCard: @Composable () -> Unit = {
                OnlineMetaNotice(
                    start = if (touch) 4.dp else 0.dp,
                    end = if (touch) 4.dp else 0.dp,
                    onTurnedOff = onOfflineAction,
                    // The pressed button goes with the note, so a remote is handed back to a video.
                    onDismissed = { if (!touch) runCatching { firstItem.requestFocus() } },
                )
            }
            // An off left over from an older version is asked about once, here rather than over
            // Home, where it would be the first thing on screen.
            OnlineMetaInvite(onAnswered = { if (!touch) runCatching { firstItem.requestFocus() } })
            fun focusOf(entry: ShelfEntry): Modifier {
                val first = if (entry.key == firstKey) Modifier.focusRequester(firstItem) else Modifier
                if (entry.key !in firstRowKeys) return first
                return first.onFocusChanged { state ->
                    if (state.isFocused) {
                        scope.launch {
                            delay(FOCUS_SETTLE_MS)
                            // Only when the list has moved off its start: asked again on every
                            // press along a row already at the top, it pulled the grid back up
                            // after each step and the row jumped.
                            // At once on a television, as every other move of the remote is there.
                            val tv = com.tmplayer.ui.theme.Motion.television
                            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                                if (tv) gridState.scrollToItem(0) else gridState.animateScrollToItem(0)
                            }
                        }
                    }
                }
            }

            // The phone's grid is compact but not captionless: two lines of the name under each
            // card, since the name is often the only thing telling two releases apart.
            val dense = touch
            // On a phone a new order, a search or a filter moves the cards to their new places and
            // fades the newcomers in, rather than redrawing the list at once. Not past a few
            // hundred, where the bookkeeping costs more than the motion is worth.
            val animateCards = touch && !lessMotion && feed.size <= ANIMATED_ITEMS_MAX
            val gap = if (touch) TileGrid.TouchGap else TileGrid.TvGap
            val padding = PaddingValues(
                start = edge,
                end = edge,
                // Room above the first row for a focused tile to grow into.
                top = if (touch) 0.dp else Tv.FocusClearance,
                // A television crops its outermost few percent, so the last row needs
                // clearance or its titles are cut off the bottom of the panel. A phone crops
                // nothing but does put a gesture bar over the last row.
                bottom = if (touch) maxOf(navigationBarPadding(), LocalBottomChrome.current) + 16.dp else Tv.SafeV + 16.dp,
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().keepTopInSight(gridState, enabled = !touch),
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    if (metaNotice) {
                        item(key = "meta-notice", span = { GridItemSpan(maxLineSpan) }) { metaNoticeCard() }
                    }
                    if (course != null || loadedOnly || filterNote || selfDestructNote) {
                        item(key = "course", span = { GridItemSpan(maxLineSpan) }) { courseHeader() }
                    }
                    shelfGrid(
                        feed = feed,
                        posterKeys = posterKeys,
                        columns = columns,
                        posterColumns = posterColumns,
                        wideColumns = wideColumns,
                        animate = animateCards,
                    ) { entry ->
                            when (entry) {
                                is MediaFeedEntry.Media -> {
                                    val shelved = entry.item
                                    if (shelved is ShelfEntry.Show) {
                                        SeriesCard(
                                            series = shelved.series,
                                            progress = remember(shelved.series, watch) { watch.progress(shelved.series) },
                                            fresh = remember(shelved.series, watch) { watch.newEpisodes(shelved.series) > 0 },
                                            onClick = {
                                                tileReturn.open(shelved.key)
                                                openSeries.open(shelved.series)
                                            },
                                            dense = dense,
                                            poster = true,
                                            onFocused = { standingOn = shelved.series.cover },
                                            // The show's menu (HX20); not while picking videos to download.
                                            onLongClick = if (selecting) null else ({
                                                tileReturn.open(shelved.key)
                                                heldSeries = shelved.series
                                            }),
                                            modifier = focusOf(shelved).returnTile(tileReturn, shelved.key),
                                        )
                                    } else {
                                    val item = (shelved as ShelfEntry.File).item
                                    MediaCard(
                                        item = item,
                                        watched = watchProgress[
                                            SettingsStore.progressKey(item.chatId, item.messageId),
                                        ],
                                        finished = SettingsStore.progressKey(item.watchedBy.chatId, item.watchedBy.messageId) in watchedVideos,
                                        dense = dense,
                                        poster = shelved.key in posterKeys,
                                        selected = if (selecting) selected.containsKey(item.id) else null,
                                        onClick = {
                                            tileReturn.open(shelved.key)
                                            if (selecting) toggle(item) else if (detailFirst) details.open(item) else onPlay(item)
                                        },
                                        onLongClick = if (selecting) null else {
                                            {
                                                tileReturn.open(shelved.key)
                                                heldMenuOf = item
                                            }
                                        },
                                        onFocused = { standingOn = item },
                                        modifier = focusOf(shelved).returnTile(tileReturn, shelved.key),
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
                    // The next page on its way: a row of placeholder tiles where it will land.
                    if (list.loadingMore) {
                        item(key = "more-skeleton", span = { GridItemSpan(maxLineSpan) }, contentType = "more-skeleton") {
                            com.tmplayer.ui.components.MediaGridSkeletonRow(wideColumns, gap, dense)
                        }
                    }
                }
                ScrollToTop(gridState)
                // The hold to scroll handle, its bubble saying where the grid is in the chat's order.
                val scrollStrings = LocalStrings.current
                val entryByKey = remember(feed) {
                    feed.mapNotNull { (it as? MediaFeedEntry.Media)?.item }.associateBy { it.key }
                }
                val scrollLabel: (Any) -> String? = { key -> entryByKey[key]?.let { ScrollLabels.video(it, order, scrollStrings) } }
                FastScroller(gridState, label = scrollLabel)

                heldSeries?.let { series ->
                    SeriesMenuOpened(
                        series = series,
                        watch = watch,
                        chatTitle = chatTitle,
                        onOpen = { openSeries.open(series) },
                        onDismiss = { heldSeries = null },
                    )
                }

                openSeries.series?.let { series ->
                    SeriesOpened(
                        series = series,
                        watch = watch,
                        onPlay = { onPlay(it) },
                        onDismiss = { openSeries.open(null) },
                        onLongClick = { heldMenuOf = it },
                        onDownload = { request ->
                            scope.launch { queueSeason(context, settings, request) { chatTitle }?.let(onOfflineAction) }
                        },
                        chatTitle = chatTitle,
                    )
                }

                heldMenuOf?.let { item ->
                    val key = SettingsStore.progressKey(item.chatId, item.messageId)
                    MediaDetailOpened(
                        item = item,
                        chatTitle = if (item.chatId == chatId) chatTitle else "",
                        watched = watchProgress[key],
                        finished = key in watchedVideos,
                        onSetWatched = { onSetWatched(item, it) },
                        onPlay = { onPlay(item) },
                        onSelectVideos = if (item.chatId != chatId) null else ({
                            selected = mapOf(item.id to item)
                            selecting = true
                        }),
                        onDownload = { downloadThese(listOf(item)) },
                        onRemoved = viewModel::refreshLocalAvailability,
                        onDismiss = { heldMenuOf = null },
                        asMenu = true,
                        onOpenDetails = { details.open(item) },
                    )
                }

                details.top?.let { entry ->
                    val item = entry.page
                    // The episode list on the page shows where the viewer is in each episode.
                    androidx.compose.runtime.CompositionLocalProvider(LocalSeriesWatch provides watch) {
                        MediaDetailOpened(
                            item = item,
                            // A video from "More like this" may be from another chat, whose name this grid does not know.
                            chatTitle = if (item.chatId == chatId) chatTitle else "",
                            watched = watchProgress[
                                SettingsStore.progressKey(item.chatId, item.messageId),
                            ],
                            finished = SettingsStore.progressKey(item.watchedBy.chatId, item.watchedBy.messageId) in watchedVideos,
                            onSetWatched = { onSetWatched(item, it) },
                            onPlay = { onPlay(item) },
                            onSelectVideos = if (item.chatId != chatId) null else ({
                                selected = mapOf(item.id to item)
                                selecting = true
                            }),
                            onDownload = { downloadThese(listOf(item)) },
                            onRemoved = viewModel::refreshLocalAvailability,
                            onDismiss = { details.clear() },
                            onOpenItem = { details.push(it) },
                            onTurnTo = { details.turn(it) },
                            onBack = if (details.canGoBack) ({ details.back() }) else null,
                            returnTo = entry.returnTo,
                        )
                    }
                }

                // Pages are fetched well ahead of the last row, and in a chat that posts each film
                // ten times a page folds into a tile or two, so the fetching goes on page after page
                // while the viewer is still near the top. The chip is for the viewer who has reached
                // the end and is waiting, not a flash for every quiet fetch ahead of them.
                val atEnd by remember { derivedStateOf { !gridState.canScrollForward } }
                // The next page's placeholder row is in the listing itself; a page that will not
                // come says so over the foot, with a way to ask again.
                if (list.loadingMore && atEnd) {
                    com.tmplayer.ui.components.SlowAnswerNotice(
                        key = list.items.size,
                        onRetry = viewModel::retryMore,
                        card = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Above the name strip when there is one, rather than through it.
                            .padding(
                                // Tv.SafeV is overscan clearance and means nothing on a phone,
                                // where the gesture bar is the real obstacle.
                                bottom = (if (touch) navigationBarPadding() else Tv.SafeV) +
                                    (if (standing) 46.dp else 0.dp) +
                                    connectionOffset,
                            ),
                    )
                }
                // The last page failed: said where the chip was, with Try again, rather than the
                // listing quietly stopping short.
                if (list.moreFailed && !list.loadingMore) {
                    Surface(
                        shape = CircleShape,
                        color = Tone.surfaceHigh,
                        contentColor = Tone.text,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = (if (touch) navigationBarPadding() else Tv.SafeV) +
                                    (if (standing) 46.dp else 0.dp) +
                                    connectionOffset,
                            ),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(s.gridMoreFailed, style = MaterialTheme.typography.bodyMedium)
                            TmButton(onClick = viewModel::retryMore) { Text(s.commonTryAgain) }
                        }
                    }
                }

                StandingOnName(
                    { standingOn },
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Tv.SafeH, bottom = Tv.SafeV + connectionOffset),
                )
            }

            // A new first item (a search answer, a refresh) asks for focus again, or the remote is
            // left with nowhere to go. Only on a remote: on a phone this would highlight a card
            // nobody pointed at. Not while the search is being typed into: the answer is what
            // changed the first item.
            LaunchedEffect(list.items.firstOrNull()?.id, touch) {
                if (!touch && !typingNow) runCatching { firstItem.requestFocus() }
            }
            // Back from a video's page the remote lands on the tile it was opened from: a page
            // of messages arriving meanwhile asked for the first tile above (Mi TV, 2026-10-23).
            ReturnToTile(tileReturn, pageOpen = details.top != null || heldMenuOf != null || openSeries.series != null) { key ->
                val at = feed.indexOfFirst { (it as? MediaFeedEntry.Media)?.item?.key == key }
                if (at < 0) return@ReturnToTile false
                // The notes the listing may start with are items of their own.
                val lead = (if (metaNotice) 1 else 0) +
                    (if (course != null || loadedOnly || filterNote || selfDestructNote) 1 else 0)
                gridState.scrollToItem(lead + at)
                true
            }

            // Fetch the next page well before the user reaches the bottom row: two rows ahead.
            val nearEnd by remember(list.items.size, columns, feed) {
                derivedStateOf {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= feed.size - columns * 2
                }
            }
            // Keyed on the feed as well: a filter, a search or the grouping changes it without a page
            // arriving, and a short grid left that way has to ask for more by itself.
            LaunchedEffect(gridState, list.items.size, columns, feed) {
                snapshotFlow { nearEnd }.collect { if (it) viewModel.loadMore() }
            }
            }
        }
      }
    }

    // Null while every ticked video comes from a chat that restricts saving content.
    val downloadSelected = { downloadThese(selected.values.toList()) }
        .takeIf { selected.isEmpty() || selected.values.any { it.canBeSaved } }

    var chatInfoOpen by remember { mutableStateOf(false) }
    if (chatInfoOpen) {
        ChatInfoSheet(chatId, chatTitle, chatPhotoFileId, chatMiniThumbnail, onDismiss = { chatInfoOpen = false })
    }

    if (touch) {
        TouchMediaScaffold(
            chatTitle = chatTitle,
            chatPhotoFileId = chatPhotoFileId,
            chatMiniThumbnail = chatMiniThumbnail,
            onChatInfo = { chatInfoOpen = true },
            isFavorite = isFavorite,
            query = query,
            onQuery = { query = it },
            onSubmit = submit,
            searchBusy = chatSearching,
            onBack = onBack,
            onToggleFavorite = onToggleFavorite,
            onRefresh = refresh,
            recentSearches = recentSearches,
            onClearRecent = clearRecent,
            onSort = { sorting = true },
            sortPopover = { anchor ->
                VideoMenuPopover(open = sorting, anchor = anchor, state = menu, onDismiss = { sorting = false })
            },
            filtered = filter != com.tmplayer.data.VideoFilter.All,
            sizeHidden = shownHidden,
            onSizeHidden = { sizePicking = true },
            topicTabs = topicBar?.let { bar ->
                { TopicTabs(bar, viewModel::selectTopic, Modifier.padding(vertical = 6.dp), edge = edge) }
            },
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
            onLeaveSelection = { leaveSelection() },
            content = {
                // The circular arrow stays in the overflow too, because a listing that is empty or
                // in an error state has nothing to drag.
                TouchPullRefresh(
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
                    onSubmit = submit,
                    onToggleFavorite = onToggleFavorite,
                    edge = edge,
                    onRefresh = refresh,
                    onBack = onBack,
                    recentSearches = recentSearches,
                    onClearRecent = clearRecent,
                    onSort = { sorting = true },
                    filtered = filter != com.tmplayer.data.VideoFilter.All,
                    sizeHidden = shownHidden,
                    onSizeHidden = { sizePicking = true },
                    onChatInfo = { chatInfoOpen = true },
                    onTyping = { typing = it },
                )
            }
            // Under the heading and over the grid, so Up from the first row of tiles reaches it.
            // Not while videos are being ticked: a pick would throw the ticked ones away.
            topicBar?.let { bar ->
                if (!selecting) TopicTabs(bar, viewModel::selectTopic, Modifier.padding(bottom = 12.dp), edge = edge)
            }
            listing()
        }
    }

    val target = reportTarget
    if (target != null && reportOptions.isNotEmpty()) {
        // Pick one reason of several, so the pickers' panel rather than a menu of actions: the
        // same panel as the language and subtitle pickers, with Close at the bottom end, and Back closing it.
        val dismiss = {
            reportTarget = null
            reportOptions = emptyList()
        }
        val touch = isTouch()
        val first = remember { FocusRequester() }
        ChoiceSheet(
            title = reportTitle.ifBlank { s.gridReportSponsored },
            note = s.gridReportSponsoredDetail,
            onDismiss = dismiss,
            onClose = dismiss,
            ignoreRelease = !touch,
        ) {
            ChoiceList {
                reportOptions.forEachIndexed { index, option ->
                    ChoiceLine(
                        title = option.text,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    ) { handleReport(target, option.id) }
                }
            }
            FocusOnOpen(first)
        }
    }
}

/**
 * The phone's version of this screen's chrome, laid out for a phone held in one hand.
 *
 * The top keeps only what is read: the chat's own picture and its name beside the round Back,
 * scrolling away with the grid and back on the first pull down. Everything pressed is at the foot,
 * in a sticky bar that stays put while the grid scrolls: Search, the star, Sort, rows or tiles,
 * and Refresh. Search turns that bar into the field, which rides up with the keyboard and back
 * down when it closes; the recent searches sit just above it. A selection turns it into the
 * selection's bar, and Back into Close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchMediaScaffold(
    chatTitle: String,
    chatPhotoFileId: Int,
    chatMiniThumbnail: ByteArray?,
    /** Opens the chat's info sheet from its name at the top; null leaves the name inert. */
    onChatInfo: (() -> Unit)? = null,
    isFavorite: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    /** A search of the chat is on its way while the old listing stays up: a spinner in the field. */
    searchBusy: Boolean = false,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    /** Offered as chips while the search is open and still empty. */
    recentSearches: List<String> = emptyList(),
    onClearRecent: () -> Unit = {},
    /** Opens the Sort menu; null leaves it out. */
    onSort: (() -> Unit)? = null,
    /** The Sort menu ([VideoMenuPopover]), drawn over everything and grown out of the Sort button ([anchor]). */
    sortPopover: (@Composable (anchor: PopoverAnchor) -> Unit)? = null,
    /** A filter is on: the Sort button is drawn in the accent, so the filtered state shows from the bar. */
    filtered: Boolean = false,
    /** Videos the size limits keep out of this chat, for the "n hidden" button; 0 leaves it out. */
    sizeHidden: Int = 0,
    /** Opens the quick size picker ([SizeQuickSheet]) from that button. */
    onSizeHidden: () -> Unit = {},
    /** The topic row of a group with topics, under the title. Null for every other chat. */
    topicTabs: (@Composable () -> Unit)? = null,
    /** Opens with the search already showing, for the screenshot fixture. */
    startSearching: Boolean = false,
    /**
     * The selection's bar, shown in place of the sticky bar while videos are being ticked; Back
     * becomes Close ([onLeaveSelection]) meanwhile.
     */
    selectionBar: (@Composable () -> Unit)? = null,
    onLeaveSelection: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    var searching by rememberSaveable { mutableStateOf(startSearching) }
    val field = remember { FocusRequester() }
    // The star ticks under the finger as it fills or empties ("Vibrate on tap").
    val tick = com.tmplayer.ui.components.rememberToggleTick()
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
    val closeSearch = {
        searching = false
        onQuery("")
        onSubmit()
    }

    // Back leaves the search first and the chat second, which is the order a phone user means it.
    BackHandler(enabled = searching) { closeSearch() }

    val compact = rememberCompactChrome()
    val density = LocalDensity.current
    // Measured from the bottom of the window: the keyboard when it is up, else the navigation bar.
    val under = with(density) {
        maxOf(WindowInsets.navigationBars.getBottom(this), WindowInsets.ime.getBottom(this)).toDp()
    }
    val recentShown = searching && query.isEmpty() && recentSearches.isNotEmpty() && selectionBar == null
    // What floats over the end of the grid, which the grid pads its last row clear of.
    val room = under + FloatingChrome.clearance(compact, labelled = !compact) + if (recentShown) RECENT_ROW else 0.dp
    val sortAnchor = rememberPopoverAnchor()
    // Each button's name under it, as on the main bar, unless the phone is on its side and short of height.
    val caption: (String) -> String? = { if (compact) null else it }

    Box(Modifier.fillMaxSize()) {
        // No scroll behaviour here: the title is a floating pill, not a TopAppBar, and a behaviour
        // with no bar to set its limit took every scroll for itself, so the grid never moved.
        Scaffold(
            // The grid runs to the foot of the window, under the bar that floats over it, and from
            // under the floating Back and title at the top, which it starts clear of.
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(top = FloatingChrome.Button + 16.dp)) {
                if (selectionBar == null) topicTabs?.invoke()
                CompositionLocalProvider(LocalBottomChrome provides room) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }

        // The chat's picture and name in a floating pill beside Back: both stay put while the
        // list scrolls under them, so where you are is always in sight.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = FloatingChrome.Inset + FloatingChrome.Button + 8.dp, top = 10.dp, end = FloatingChrome.Inset)
                .height(FloatingChrome.Button)
                .shadow(FloatingChrome.Shadow, CircleShape)
                .clip(CircleShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), CircleShape)
                // The name is the way to the chat's info: its picture, username and description.
                .then(if (onChatInfo != null) Modifier.clickable(onClickLabel = LocalStrings.current.chatsInfoAbout, onClick = onChatInfo) else Modifier)
                // The 36 dp picture 4 dp clear of the 44 dp pill all round its end.
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaPreview(
                miniThumbnail = chatMiniThumbnail,
                thumbnailFileId = chatPhotoFileId,
                fallbackLabel = chatTitle,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            M3Text(
                chatTitle,
                style = M3MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Tone.text,
            )
        }

        FloatingBackButton(
            icon = when {
                selectionBar != null -> TmIcons.Close
                else -> TmIcons.ArrowBack
            },
            label = when {
                selectionBar != null -> s.gridStopSelecting
                searching -> s.browseCloseSearch
                else -> s.gridBackToChats
            },
            onClick = {
                when {
                    selectionBar != null -> onLeaveSelection()
                    searching -> closeSearch()
                    else -> onBack()
                }
            },
            modifier = Modifier.align(Alignment.TopStart),
        )

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (recentShown) {
                // Just over the field, where the thumb already is.
                TouchRecentSearches(
                    searches = recentSearches,
                    onPick = {
                        onQuery(it)
                        onSubmit()
                    },
                    onClear = onClearRecent,
                )
            }
            when {
                selectionBar != null -> selectionBar()
                searching -> StickyActionBar(compact = compact, fill = true) {
                    FloatingBarIcon(TmIcons.ArrowBack, s.browseCloseSearch, closeSearch)
                    MediaSearchField(
                        query = query,
                        // Only the text: the search follows once the typing rests, not per letter.
                        onQueryChange = onQuery,
                        onSubmit = onSubmit,
                        busy = searchBusy,
                        onVoiceSearch = startVoice,
                        modifier = Modifier.focusRequester(field),
                        rowModifier = Modifier.weight(1f),
                    )
                }
                else -> StickyActionBar(compact = compact, labelled = !compact) {
                    FloatingBarIcon(
                        icon = if (isFavorite) TmIcons.Star else TmIcons.StarOutline,
                        label = if (isFavorite) s.browseRemoveFavourite else s.browseAddFavourite,
                        onClick = { tick(!isFavorite); onToggleFavorite() },
                        // Only the filled star is coloured, so the outline sits with the others.
                        tint = if (isFavorite) Tone.accent else Tone.text,
                        checked = isFavorite,
                        caption = caption(s.gridStarShort),
                        // The star pops as it fills and dips as it empties.
                        glyph = { StarIcon(isFavorite, TmIcons.Star, TmIcons.StarOutline, on = Tone.accent, off = Tone.text) },
                    )
                    // Says that the size limits hide some, and changes them, from the chat itself.
                    if (sizeHidden > 0) {
                        FloatingBarIcon(
                            TmIcons.FilterHorizontal,
                            s.gridSizeHiddenLabel(sizeHidden),
                            onSizeHidden,
                            tint = Tone.accent,
                            caption = s.gridSizeHidden(sizeHidden),
                        )
                    }
                    // Search and Sort at the end, Sort last, where the thumb reaches first.
                    FloatingBarIcon(TmIcons.Search, s.gridSearchThisChat, { searching = true }, caption = caption(s.commonSearch))
                    onSort?.let {
                        FloatingBarIcon(
                            TmIcons.Sort,
                            if (filtered) s.gridFilterOn else s.gridSort,
                            it,
                            modifier = sortAnchor.modifier,
                            tint = if (filtered) Tone.accent else Tone.text,
                            caption = caption(s.gridSort),
                        )
                    }
                }
            }
        }
    }

    sortPopover?.invoke(sortAnchor)

    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        // The field is the bar at the foot, so it is on screen whatever the grid has done; the
        // keyboard comes up with it and the field rides up on top of the keyboard.
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
}

@Composable
private fun MediaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    busy: Boolean,
    onVoiceSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Not a Material SearchBar: that component owns the whole top of the screen, arrow and
    // suggestion sheet included, and this search sits inside the sticky bar at the foot. It
    // borrows the shape and the colour so it still reads as a search bar.
    Surface(
        // The sticky bar is the field's own pill: a second, lighter pill inside it read as two
        // controls stacked, so this one draws no fill of its own.
        color = Color.Transparent,
        contentColor = Tone.text,
        modifier = rowModifier.padding(end = 4.dp),
    ) {
        Row(
            Modifier
                .padding(
                    start = 4.dp,
                    // A trailing icon brings its own room with it; without one the text would run
                    // into the rounded end of the field.
                    end = if (query.isEmpty() && onVoiceSearch == null) 16.dp else 4.dp,
                )
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The text and caret held here, the screen told of each edit (HeldText): a late
            // answer never drags the caret back.
            val held = com.tmplayer.ui.components.rememberHeldText(query)
            BasicTextField(
                value = held.value,
                onValueChange = { held.edit(it, onQueryChange) },
                singleLine = true,
                textStyle = M3MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
                cursorBrush = SolidColor(Tone.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                modifier = modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        M3Text(
                            s.gridSearchThisChat,
                            style = M3MaterialTheme.typography.bodyLarge,
                            color = Tone.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            if (busy && query.isNotEmpty()) {
                // Shown only once Telegram is asked, never while the typing settles.
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = 4.dp).size(18.dp),
                )
            }
            if (query.isNotEmpty()) {
                com.tmplayer.ui.components.IconTip(s.browseClearSearch) {
                    IconButton(onClick = { onQueryChange("") }) {
                        M3Icon(TmIcons.Close, contentDescription = s.browseClearSearch)
                    }
                }
            } else if (onVoiceSearch != null) {
                com.tmplayer.ui.components.IconTip(s.browseSearchByVoice) {
                    IconButton(onClick = onVoiceSearch) {
                        M3Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice)
                    }
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
    /** How far in from the side the header starts, which is overscan on a TV and taste on a phone. */
    edge: Dp,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    /** Offered as a row of pills while the remote is in the header and nothing is typed. */
    recentSearches: List<String> = emptyList(),
    onClearRecent: () -> Unit = {},
    /** Shows the recent row whatever has focus, for the screenshot fixture. */
    alwaysShowRecent: Boolean = false,
    /** Opens the Sort menu ([VideoSortSheet]); null leaves it out. */
    onSort: (() -> Unit)? = null,
    /** A filter is on: the Sort pill's glyph is drawn in the accent. */
    filtered: Boolean = false,
    /** Videos the size limits keep out of this chat, for the "n hidden" pill; 0 leaves it out. */
    sizeHidden: Int = 0,
    /** Opens the quick size picker ([SizeQuickSheet]) from that pill. */
    onSizeHidden: () -> Unit = {},
    /** Opens the chat's info panel, from a button beside its name; null leaves it out. */
    onChatInfo: (() -> Unit)? = null,
    /** Told whether the search field is being typed into (its text field holds the focus). */
    onTyping: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // On Fire TV the voice button opens the keyboard, which the remote's mic dictates into.
    var keyboardRequests by remember { mutableStateOf(0) }
    val startVoice = rememberVoiceSearch(s.gridVoicePromptVideo, onKeyboard = { keyboardRequests++ }) {
        onQuery(it)
        onSubmit()
    }

    Column(modifier.padding(start = edge, end = edge, top = Tv.SafeV, bottom = 12.dp)) {
        // The same picture the chat was picked by, so it is obvious which one this listing is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The way out, drawn. The remote's Back key works, but Back is the key stick owners
            // are least sure of, so the screen says it as well.
            Pill(s.gridBackToChats, TmIcons.ArrowBack, showLabel = false, onClick = onBack)
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
            if (onChatInfo != null) {
                Spacer(Modifier.width(16.dp))
                Pill(s.chatsInfoAbout, TmIcons.Info, showLabel = false, onClick = onChatInfo)
            }
        }
        Spacer(Modifier.height(12.dp))
        val searchField = remember { FocusRequester() }
        var sizePillFocused by remember { mutableStateOf(false) }
        LaunchedEffect(sizeHidden > 0) {
            if (sizeHidden == 0 && sizePillFocused) {
                sizePillFocused = false
                runCatching { searchField.requestFocus() }
            }
        }
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
                // The field's own box focused is the remote passing by; the text field inside it
                // focused is typing.
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchField)
                    .onFocusChanged { onTyping(it.hasFocus && !it.isFocused) },
            )
            if (startVoice != null) {
                // The microphone says what it does, so no label takes room from the search field.
                Pill(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)
            }
            if (query.isNotBlank()) {
                Pill(s.commonClear, TmIcons.Close) {
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
                icon = if (isFavorite) TmIcons.Star else TmIcons.StarOutline,
                tintWhenIdle = if (isFavorite) Tone.accent else Tone.text,
                starred = isFavorite,
                onClick = onToggleFavorite,
            )
            if (sizeHidden > 0) {
                // Labelled, as the one pill here that says something rather than does it. When a
                // new limit takes it away while it has the focus, the search field takes it.
                Box(Modifier.onFocusChanged { sizePillFocused = it.hasFocus }) {
                    Pill(s.gridSizeHidden(sizeHidden), TmIcons.FilterHorizontal, tintWhenIdle = Tone.accent, onClick = onSizeHidden)
                }
            }
            onSort?.let { Pill(if (filtered) s.gridFilterOn else s.gridSort, TmIcons.Sort, tintWhenIdle = if (filtered) Tone.accent else Tone.text, showLabel = false, onClick = it) }
            // Telegram pushes new messages into TDLib's database, but this grid was built from a
            // search that ran when it opened, so a video posted since then needs a fresh search.
            // Icon only, like the refresh on the chat list: a label costs the search field 90dp.
            val spin = com.tmplayer.ui.components.rememberRefreshSpin()
            Pill(s.commonRefresh, TmIcons.Refresh, showLabel = false, spin = spin, onClick = {
                spin.kick()
                onRefresh()
            })
        }
        if (query.isBlank() && recentSearches.isNotEmpty() && (headerFocused || alwaysShowRecent)) {
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.gridRecentSearchesLabel, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                for (recent in recentSearches) {
                    Pill(recent, TmIcons.History, plain = true) {
                        // The row goes as soon as there is a query, so focus moves first, as Clear does.
                        runCatching { searchField.requestFocus() }
                        onQuery(recent)
                        onSubmit()
                    }
                }
                Pill(s.gridClearRecentSearches, TmIcons.Close, showLabel = false) {
                    runCatching { searchField.requestFocus() }
                    onClearRecent()
                }
            }
        }
        }
    }
}

/**
 * The phone's recent searches: a scrolling row of chips just over the open search field, with
 * Clear at the end. Shown only while the field is empty, which is when a chip saves typing.
 */
@Composable
private fun TouchRecentSearches(
    searches: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val s = LocalStrings.current
    // On a panel of its own: over the list the chips would sit on whatever rows are behind them,
    // and neither would read.
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Tone.surfaceHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = TOUCH_EDGE).padding(bottom = 8.dp),
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (recent in searches) {
            // Plain words on the panel, no box each: the panel already says these go together.
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onPick(recent) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                M3Icon(TmIcons.History, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(18.dp))
                M3Text(recent, style = M3MaterialTheme.typography.bodyMedium, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onClear) { M3Text(s.commonClear) }
    }
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
        // At the foot, where the sticky bar was; Close is the round button at the top start.
        StickyActionBar(compact = rememberCompactChrome(), fill = true) {
            M3Text(
                label,
                style = M3MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            TextButton(onClick = onSelectAll) { M3Text(s.gridSelectAll, maxLines = 1) }
            // Words, not a bare arrow: this is the one control on the bar that acts on the
            // ticked videos, and a corner glyph would say nothing about what it starts.
            if (onDownload != null) Button(
                onClick = onDownload,
                enabled = count > 0,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.padding(end = 2.dp),
            ) {
                M3Icon(
                    TmIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                M3Text(s.gridDownloadSelected, maxLines = 1)
            }
        }
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
        Pill(s.gridSelectAll, TmIcons.Check, onClick = onSelectAll)
        Pill(s.commonCancel, TmIcons.Close, onClick = onCancel)
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
    /** Plain words until the remote is on it: a recent search, with no box of its own. */
    plain: Boolean = false,
    /** The favourite pill's: the star pops as it fills ([StarIcon]) instead of a plain icon. */
    starred: Boolean? = null,
    /** The refresh pill's: its arrow turns once for each press ([RefreshGlyph]). */
    spin: com.tmplayer.ui.components.RefreshSpin? = null,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else if (plain) Color.Transparent else Tone.surfaceHigh,
        animationSpec = tween(140),
        label = "pill",
    )
    val foreground = if (focused) Tone.onFocusFill else tintWhenIdle
    // A remote's answer to a press is the focus moving, so the pill the remote lands on grows a
    // touch, as a card does; nothing sinks, since nothing is under a finger.
    val lessMotion = com.tmplayer.ui.components.rememberLessMotion()
    val scale by animateFloatAsState(
        if (focused && !lessMotion) PILL_FOCUS_SCALE else 1f,
        Motion.standard(Motion.Fast),
        label = "pillScale",
    )

    // An icon on its own says its name under it while the remote is on it, as the rail's icons do.
    Box {
        if (!showLabel && !isTouch()) com.tmplayer.ui.components.FocusTip(focused, label)
        Row(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
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
            if (starred != null) {
                StarIcon(starred, TmIcons.Star, TmIcons.StarOutline, on = foreground, off = foreground, size = 22.dp)
            } else if (spin != null) {
                com.tmplayer.ui.components.RefreshGlyph(spin, if (showLabel) null else label, Modifier.size(22.dp), tint = foreground)
            } else {
                Icon(icon, contentDescription = if (showLabel) null else label, tint = if (plain && !focused) Tone.muted else foreground, modifier = Modifier.size(22.dp))
            }
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
            }
        }
    }
}

/** How much a pill in a chat's header grows under the remote: less than a card, being small. */
private const val PILL_FOCUS_SCALE = 1.06f

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
 * A video's tile: the art first, with the name and the facts under it and no panel round any of it.
 *
 * The same tile on the phone, in a chat's grid and on Home, and on the television; [dense] is the
 * phone's, with two lines of name and the running time on the art in place of the line of facts.
 * Focus and the press are drawn on the art alone (see [VideoTile]).
 *
 * [badge] is the caller's own mark for the art's top end corner, a "New" say, where the quality
 * tags used to sit before they moved into the line of facts. The top start corner is this tile's:
 * the download queue's state, else where the copy is, else the watched tick.
 */
@Composable
internal fun MediaCard(
    item: MediaItem,
    watched: WatchPoint?,
    onClick: () -> Unit,
    /** Null for a card nothing listens to, which then keeps no watcher on its focus. */
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** On the Watched list, or marked watched: the tick in the art's corner. */
    finished: Boolean = false,
    /** The phone's tile: two lines of name, always, and the smaller badges. */
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
    badge: (@Composable () -> Unit)? = null,
    /**
     * A film's 2:3 poster card ([SeriesShelf.isFilm]) rather than a video's 16:9 card. The caller
     * says, since only it knows whether the row or grid around it has room for a tall card.
     */
    poster: Boolean = false,
    /** What the caption says in place of the file's own name: Home's New episodes row names the episode. */
    title: String? = null,
    /** The line of facts in place of the file's own (Home's New episodes row: the chat, quality and size). */
    meta: (@Composable (ink: androidx.compose.ui.graphics.ColorProducer) -> Unit)? = null,
) {
    // This video's correction alone, from the screen's one collector ([LocalTileShared]).
    val renamed = com.tmplayer.ui.components.rememberCorrection(item.id)?.title
    val named = if (poster) remember(item, renamed) { SeriesShelf.posterTitle(item) } else tileTitle(item)
    VideoTile(
        title = title ?: named,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        dense = dense,
        poster = poster,
        selected = selected,
        // Every video card, in a chat's grid, Home and search: its stream warmed on focus or press.
        modifier = modifier.then(Modifier.warmUpOnIntent(item)),
        meta = meta ?: { ink -> MetaLine(item, watched, ink, finished = finished, poster = poster) },
    ) {
        MediaArt(
            item = item,
            watched = watched,
            finished = finished,
            compact = dense,
            selected = selected,
            badge = badge,
            poster = poster,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The frame every tile in a grid or a row shares, a video's ([MediaCard]) and a show's
 * ([SeriesCard]): the art clipped to [Corner.Medium], 16:9 for a video and 2:3 for a [poster],
 * then the name, then a line of facts.
 *
 * No fill and no hairline at rest, on any platform: the art's own edge is the tile's, and the art
 * is never white (a picture, or a dark [com.tmplayer.ui.components.GeneratedCover]). Focus is three
 * things at once, never colour alone: the art grows by [Motion.FocusScale] about its centre, a
 * [Focus.Ring] in the accent goes round it, and the line of facts goes from muted to the full
 * text colour, all over [Motion.Fast]. A finger presses the art down to [Motion.PressScale] and
 * its ripple stays inside the art. With the system's animations off nothing scales; the ring and
 * the colour still say where focus is.
 *
 * [meta] draws the line of facts in the colour it is handed. A video's name takes two lines on a
 * phone ([dense]) and one on a television; a poster's always reserves two, in a smaller type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    poster: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Null for a tile nothing listens to, which then keeps no watcher on its focus. */
    onFocused: (() -> Unit)? = null,
    selected: Boolean? = null,
    meta: (@Composable (ink: androidx.compose.ui.graphics.ColorProducer) -> Unit)? = null,
    /** False for a card that writes its name on the art (Home's Continue watching), keeping only [meta] under it. */
    showTitle: Boolean = true,
    art: @Composable BoxScope.() -> Unit,
) {
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    // Kept as a State and, on a television, read only where it is drawn (the ring, the grow and
    // the caption's colour), so a press of the remote composes neither the tile it leaves nor the
    // one it reaches: on a 1 GB stick each tile composed again cost a frame of its own.
    val focusedState = interactions.collectIsFocusedAsState()
    val still = rememberReducedMotion()
    val latestOnFocused = androidx.compose.runtime.rememberUpdatedState(onFocused)
    if (onFocused != null) {
        LaunchedEffect(focusedState) {
            snapshotFlow { focusedState.value }.collect { if (it) latestOnFocused.value?.invoke() }
        }
    }
    val accent = Tone.accent
    val text = Tone.text
    val muted = Tone.muted
    val shape = RoundedCornerShape(Corner.Medium)
    val scale: () -> Float
    val ink: androidx.compose.ui.graphics.ColorProducer
    val ring: Modifier
    if (touch) {
        val focused by focusedState
        val pressed by interactions.collectIsPressedAsState()
        val grown = animateFloatAsState(
            targetValue = when {
                still -> 1f
                focused -> Motion.FocusScale
                pressed -> Motion.PressScale
                else -> 1f
            },
            animationSpec = Motion.standard(PRESS_MS),
            label = "tileScale",
        )
        scale = { grown.value }
        val inkState = animateColorAsState(if (focused) text else muted, Motion.standard(Motion.Fast), label = "tileInk")
        ink = remember(inkState) { androidx.compose.ui.graphics.ColorProducer { inkState.value } }
        val ringColor by animateColorAsState(if (focused) accent else Color.Transparent, Motion.standard(Motion.Fast), label = "tileRing")
        ring = Modifier.border(Focus.Ring, ringColor, shape)
    } else {
        // At once on a television, with no animation behind it: grown over a few frames, every
        // press drew the row again for each of them, which a TV stick's GPU felt more than
        // anything else on Home.
        scale = remember(focusedState, still) { { if (!still && focusedState.value) Motion.FocusScale else 1f } }
        ink = remember(focusedState, text, muted) { androidx.compose.ui.graphics.ColorProducer { if (focusedState.value) text else muted } }
        // Drawn only round the focused tile: a transparent ring was still a rounded stroke drawn
        // on every tile in view.
        ring = Modifier.focusRingDrawn(focusedState, accent, shape)
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (touch) {
                    // The ripple is drawn on the art below rather than across the whole column.
                    Modifier.secondaryPress(onLongClick).combinedClickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.holdOrPress(interactions, onClick, onLongClick)
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                // The grow and the rounded corners in one layer: a scale layer with a clip layer
                // inside it was two render nodes a tile, every tile on screen, every frame.
                .graphicsLayer {
                    val grow = scale()
                    scaleX = grow
                    scaleY = grow
                    this.shape = shape
                    clip = true
                }
                .then(if (touch) Modifier.indication(interactions, LocalIndication.current) else Modifier)
                .then(ring)
                .selectionEdge(selected, shape),
            content = art,
        )
        Spacer(Modifier.height(if (touch) 8.dp else 10.dp))
        // Two lines, and always two where a row of tiles sits under this one, so the pictures
        // beneath a short name stay in a straight line with the rest.
        val lines = if (poster || dense) 2 else 1
        if (!showTitle) {
            // The name is on the art; the facts under it are the whole caption.
        } else if (touch) {
            M3Text(
                title,
                style = if (poster) M3MaterialTheme.typography.bodyMedium else M3MaterialTheme.typography.titleSmall,
                color = Tone.text,
                maxLines = lines,
                minLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                title,
                style = if (poster) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = lines,
                minLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meta != null) {
            Spacer(Modifier.height(2.dp))
            meta(ink)
        }
    }
}

/**
 * [Focus.Ring] round a television's tile, painted from [focused] in the draw phase alone, so a
 * focus move draws the tile again and composes nothing. The stroke is the one
 * [androidx.compose.foundation.border] draws for a rounded shape (whole pixels wide, inset by half
 * of that, the corners shrunk to match), so the ring looks as it did when it was a border.
 */
private fun Modifier.focusRingDrawn(focused: androidx.compose.runtime.State<Boolean>, color: Color, shape: Shape): Modifier =
    drawWithCache {
        val stroke = kotlin.math.min(kotlin.math.ceil(Focus.Ring.toPx()), kotlin.math.ceil(size.minDimension / 2))
        val half = stroke / 2
        val outline = shape.createOutline(size, layoutDirection, this)
        val corner = (outline as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect?.topLeftCornerRadius?.let {
            androidx.compose.ui.geometry.CornerRadius((it.x - half).coerceAtLeast(0f), (it.y - half).coerceAtLeast(0f))
        }
        val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        onDrawWithContent {
            drawContent()
            if (!focused.value) return@onDrawWithContent
            if (corner != null) {
                drawRoundRect(
                    color,
                    topLeft = androidx.compose.ui.geometry.Offset(half, half),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = corner,
                    style = style,
                )
            } else {
                drawOutline(outline, color, style = style)
            }
        }
    }

/**
 * A line of facts under a tile in the tile's [ink]. On a television the colour is read where the
 * text is drawn, so the tile's focus repaints the line without composing it again; on a phone and
 * the desktop it is read here, as the animated colour always was.
 */
@Composable
internal fun TileCaption(text: String, style: androidx.compose.ui.text.TextStyle, ink: androidx.compose.ui.graphics.ColorProducer) {
    if (isTouch()) {
        M3Text(text, style = style, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        androidx.compose.foundation.text.BasicText(text, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A History video (Continue watching, Watch later, Watched) as a list row: the same frame, poster
 * and type as the chat list's rows.
 *
 * [fraction] lays the progress line on the poster's foot while a video is part way through;
 * [finished] puts the watched tick in the poster's corner instead. [detail] is what the tab says
 * about the video (where it stopped, when it was watched, how long it is), and [chatTitle] the
 * chat it came from, on a line of its own so neither one cuts the other short.
 */
@Composable
internal fun VideoListRow(
    item: MediaItem,
    title: String,
    detail: String,
    chatTitle: String,
    fraction: Float,
    finished: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = isTouch()
    // MediaArt draws the line from a watch point, so the fraction is handed over as one.
    val watched = if (!finished && fraction > 0f) WatchPoint(positionMs = (fraction * 10_000).toLong(), durationMs = 10_000) else null
    ListRow(
        onClick = onClick,
        onFocused = {},
        modifier = modifier,
        onLongClick = onHold,
        art = { artModifier ->
            MediaArt(item, watched, artModifier, compact = touch, finished = finished, poster = true, thumb = true)
        },
    ) {
        Text(
            title,
            style = if (touch) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = Tone.text,
            maxLines = if (touch) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                // Accent while there is a place to go back to, as a show's row marks its progress.
                color = if (watched != null) Tone.accent else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chatTitle.isNotBlank()) {
            Text(
                chatTitle,
                style = MaterialTheme.typography.labelMedium,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A list row's poster on its own, for a row drawn somewhere else (a download's card): the same
 * small 2:3 picture as [VideoListRow], with the progress line when [fraction] is above nothing.
 * No "Downloaded" or "Cached" corner: the page it sits on already says which the video is.
 */
@Composable
internal fun VideoPosterThumb(item: MediaItem, fraction: Float, modifier: Modifier = Modifier) {
    val touch = isTouch()
    val watched = if (fraction > 0f) WatchPoint(positionMs = (fraction * 10_000).toLong(), durationMs = 10_000) else null
    MediaArt(
        item.copy(onDevice = false),
        watched,
        modifier
            .width(if (touch) TOUCH_ROW_POSTER_WIDTH else ROW_POSTER_WIDTH)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(Corner.Small)),
        compact = touch,
        poster = true,
        thumb = true,
    )
}

/**
 * The frame of a row in a chat's list: a small 2:3 [art] at the start and [text] taking the rest of
 * the width. No card behind a row on either: on a television the row the remote is on fills and
 * takes the focus edge, the others are the poster and its words on the page's own background.
 */
@Composable
private fun ListRow(
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    art: @Composable (Modifier) -> Unit,
    text: @Composable ColumnScope.() -> Unit,
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
                            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
                            .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
                            .selectionEdge(selected, RoundedCornerShape(Corner.Medium))
                            .holdOrPress(interactions, onClick, onLongClick)
                    },
                )
                .padding(if (touch) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            art(
                Modifier
                    .width(if (touch) TOUCH_ROW_POSTER_WIDTH else ROW_POSTER_WIDTH)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(Corner.Small)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = text)
        }
    }

    if (touch) {
        // No card: rows of a small poster and its words read as a list on spacing alone, and a
        // card around each one only boxed the text in. A ticked row is tinted and edged instead.
        Box(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Medium))
                .then(
                    if (selected == true) {
                        Modifier
                            .background(Tone.accent.copy(alpha = 0.12f))
                            .border(SELECTED_EDGE, Tone.accent, RoundedCornerShape(Corner.Medium))
                    } else {
                        Modifier
                    },
                )
                .longPressable(onClick, onLongClick),
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

/**
 * A video's art with what a tile says on it: one corner badge, the quality on a film's poster,
 * where playback stopped, and the selection mark.
 *
 * A file with no picture gets its [com.tmplayer.ui.components.GeneratedCover] rather than a letter.
 */
@Composable
private fun MediaArt(
    item: MediaItem,
    watched: WatchPoint?,
    modifier: Modifier = Modifier,
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
    /** On the Watched list, or marked watched: the tick, when no other badge has the corner. */
    finished: Boolean = false,
    /** The caller's own mark for the top end corner; see [MediaCard]. */
    badge: (@Composable () -> Unit)? = null,
    /** A film's 2:3 poster: see [PosterArt]. */
    poster: Boolean = false,
    /**
     * A list row's small thumbnail, beside a title and a facts line that already say the name and
     * the quality: no quality badge and no name drawn on a generated cover, either of which would
     * fill most of a picture this size.
     */
    thumb: Boolean = false,
) {
    val s = LocalStrings.current
    val named = rememberCover(item)
    // The phone's small card writes the name under the art, and a name on the cover as well
    // would be the same words twice a few dp apart, cut short in both places.
    val cover = if ((compact && !poster) || thumb) remember(named) { named.copy(title = "") } else named
    val inset = if (compact) 4.dp else TileBadgeInset
    // Whether the answer is still on its way too: a television holds the thumbnail back for it.
    val meta = com.tmplayer.ui.online.rememberMetaState(item, showOnly = true)
    Box(modifier) {
        if (poster) {
            PosterArt(
                item.miniThumbnail,
                item.thumbnailFileId,
                cover,
                meta.value?.posterUrl,
                Modifier.fillMaxSize(),
                compactCover = compact,
                posterPending = meta.pending,
            )
        } else {
            // On Home, the show's or the film's own picture over the frame once a match arrives.
            com.tmplayer.ui.online.OnlineArt(item, Modifier.fillMaxSize()) {
                MediaPreview(
                    miniThumbnail = item.miniThumbnail,
                    thumbnailFileId = item.thumbnailFileId,
                    fallbackLabel = item.title,
                    modifier = Modifier.fillMaxSize(),
                    cover = cover,
                )
            }
        }

        // What the download queue is doing with this video, if anything. Read through
        // [derivedStateOf] so a grid of forty tiles does not all recompose once a second because
        // one of them is counting bytes: only the tile whose own entry changed is invalidated.
        // The screen's one collector where it has one ([com.tmplayer.ui.components.LocalTileShared]).
        val queue = com.tmplayer.ui.components.rememberDownloadQueue()
        val coming by remember(item.fileId) {
            androidx.compose.runtime.derivedStateOf { queue.value[item.fileId] }
        }
        // One badge in the corner at most, the most pressing first: a video being fetched, then
        // where its copy is, then whether it has been watched. Two in one corner would overlap,
        // and a badge in every corner turns the picture into a form.
        val corner = Modifier.align(Alignment.TopStart).padding(inset)
        val waiting = coming
        when {
            waiting != null -> TileBadge(
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
                corner,
                fill = if (waiting.stage == OfflineDownloads.Stage.Failed) Tone.danger.copy(alpha = 0.92f) else TileBadgeFill,
            )
            // Two words for two kinds of copy: one the viewer kept, and one playing left behind
            // that the next play may take.
            item.onDevice -> TileBadge(
                if (item.locality == MediaItem.Locality.Downloaded) s.gridBadgeDownloaded else s.gridBadgeCached,
                corner,
            )
            finished -> WatchedBadge(corner)
        }
        // The top end corner: the caller's own mark, else on a poster the quality the name
        // states, which a film's card shows where a video's carries it in its line of facts.
        val quality = item.qualityTags.firstOrNull()
        when {
            badge != null -> Box(Modifier.align(Alignment.TopEnd).padding(inset)) { badge() }
            // Only on its own: two chips across a narrow poster would meet in the middle.
            poster && !thumb && quality != null && waiting == null && !item.onDevice && !finished -> TileBadge(quality, Modifier.align(Alignment.TopEnd).padding(inset))
        }

        // Where playback stopped, as a line laid on the foot of the picture. Only with a saved
        // place: a finished video says so with its tick, and a full line would say it twice.
        val fraction = watched?.takeIf { it.positionMs > 0 }?.fraction ?: 0f
        if (fraction > 0f) {
            ProgressLine(fraction, Modifier.align(Alignment.BottomCenter), inset = if (compact) 6.dp else ProgressLineInset)
        }
        // The bottom end corner: the title's one score, only when it is already on the device.
        if (!thumb) {
            com.tmplayer.ui.online.CardScore(
                meta.value,
                Modifier.align(Alignment.BottomEnd).padding(end = inset, bottom = inset + if (fraction > 0f) 6.dp else 0.dp),
                compact = compact,
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
                        TmIcons.Check,
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
 * The full name of the video the remote stands on, along the bottom of the grid. [standingOn] is
 * read here, in this scope alone, since it changes with every move of the remote.
 */
@Composable
private fun StandingOnName(standingOn: () -> MediaItem?, modifier: Modifier) {
    val item = standingOn() ?: return
    FullName(name = item.title, modifier = modifier)
}

/**
 * The name a tile goes by: see [SeriesShelf.tileTitle]. The release name with its tags stays in
 * the detail panel, the list arrangement and the TV's name strip, which are for reading it whole.
 */
@Composable
private fun tileTitle(item: MediaItem): String {
    // A rename redraws the tile it names, and only that tile.
    val renamed = com.tmplayer.ui.components.rememberCorrection(item.id)?.title
    return remember(item, renamed) { SeriesShelf.tileTitle(item) }
}

/**
 * Click, and on a phone long press, on whatever this is applied to.
 *
 * [combinedClickable] rather than `Card(onClick =)` because a card's own click has nowhere to hang
 * a long press. Where there is no long press to hang, this is a plain clickable and the ripple is
 * the same one either way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.longPressable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) clickable(onClick = onClick)
    else secondaryPress(onLongClick).combinedClickable(onClick = onClick, onLongClick = onLongClick)

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

/**
 * Running time, file size or the number of copies, the quality the name states, and where playback
 * stopped, on the one line under a tile and in a list row. [ink] is the tile's: muted at rest and
 * the full text colour under focus.
 */
@Composable
private fun MetaLine(
    item: MediaItem,
    watched: WatchPoint?,
    ink: androidx.compose.ui.graphics.ColorProducer,
    finished: Boolean = false,
    /** A film's poster: the year first, and no quality, which the poster wears on its art. */
    poster: Boolean = false,
) {
    val s = LocalStrings.current
    // Worked out once per change of what it says, not on every composition of the tile.
    val text = remember(item, watched, finished, poster, s) {
        val year = if (poster) item.parsedName.year else null
        val duration = s.formatter.duration(item.durationSec.toLong())
        // A tile standing for several copies says how many rather than one copy's size.
        val size = if (item.versions.size > 1) s.detailVersions(item.versions.size) else com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(item.sizeBytes))
        val resume = watched
            ?.takeIf { it.positionMs > 0 }
            ?.let { s.gridStoppedAt(s.formatter.clock(it.positionMs)) }
            ?: s.commonWatched.takeIf { finished }
        listOfNotNull(
            year?.toString(),
            // A poster is narrow: the year and the size or the copies say most, and the detail the rest.
            duration.ifEmpty { null }?.takeIf { !poster },
            size.ifEmpty { null },
            // On a video's card the quality is here rather than a second badge on the picture.
            item.qualityTags.firstOrNull()?.takeIf { !poster },
            resume,
        ).joinToString("  ·  ")
    }
    val style = if (isTouch()) {
        if (poster) M3MaterialTheme.typography.labelSmall else M3MaterialTheme.typography.labelMedium
    } else {
        if (poster) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    }
    TileCaption(text, style, ink)
}

/** No overscan to clear on a phone, so the margin is only what keeps art off the bezel. */
internal val TOUCH_EDGE = 16.dp

private val ROW_POSTER_WIDTH = 72.dp
private val TOUCH_ROW_POSTER_WIDTH = 56.dp

/**
 * How much of a list row the art takes on a phone, where a width in dp cannot work: the same row
 * is 360dp upright and twice that on its side. Two fifths leaves a thumbnail big enough to
 * recognise and a title wide enough to read at both.
 */
private const val TOUCH_ART_SHARE = 0.4f

/** The chat's picture in the app bar, at Material's own app-bar avatar size. */
private val BAR_AVATAR = 40.dp

/** The recent searches over the open search field: one row of chips. */
private val RECENT_ROW = 48.dp

/** The longest chat listing whose cards move to a new order rather than being redrawn in it. */
private const val ANIMATED_ITEMS_MAX = 300

/** How long a finger takes to press a tile's art down to [Motion.PressScale]. */
private const val PRESS_MS = 100

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

/** The grid's listing once arranged: the entries in order, the caption lines to skip, and which are posters. */
internal data class Shelved(
    val shelf: List<ShelfEntry>,
    val repeatedCaptions: Set<String>,
    val posterKeys: Set<String>,
    /** How many entries there were before the filter, for "Showing Unwatched: 3 of 40". */
    val total: Int = shelf.size,
)

/**
 * What a chat's listing draws, in the chat's own order: shows folded into one entry each while
 * [grouping], and copies of one film folded into one (led by the copy the viewer started). Films,
 * shows and other videos are not split apart, and in the grid every card is a portrait poster
 * ([SeriesShelf.posterKeys]). Shared with the promo fixture, so its shots are of
 * this arrangement and not a copy of it.
 */
internal fun arrangeShelf(
    items: List<MediaItem>,
    watchProgress: Map<String, WatchPoint>,
    grouping: Boolean,
    /** The chat's order (see [com.tmplayer.data.VideoSorting]); the chat's own by default. */
    sort: com.tmplayer.data.VideoSort = com.tmplayer.data.VideoSort.Newest,
    /** The Watched list's keys, keyed like [watchProgress], for "Unwatched first". */
    watchedKeys: Set<String> = emptySet(),
    corrections: Map<String, com.tmplayer.data.VideoCorrection> = com.tmplayer.data.VideoCorrections.current,
    /** Which entries are listed (see [com.tmplayer.data.VideoFiltering]); every one by default. */
    filter: com.tmplayer.data.VideoFilter = com.tmplayer.data.VideoFilter.All,
): Shelved {
    val progressOf: (MediaItem) -> com.tmplayer.data.VideoSorting.Progress = { item ->
        val key = SettingsStore.progressKey(item.chatId, item.messageId)
        when {
            key in watchedKeys -> com.tmplayer.data.VideoSorting.Progress.Watched
            key in watchProgress -> com.tmplayer.data.VideoSorting.Progress.InProgress
            else -> com.tmplayer.data.VideoSorting.Progress.Untouched
        }
    }
    val arranged = if (grouping) {
        SeriesShelf.arrange(items, corrections)
    } else {
        SeriesShelf.foldFilmCopies(items, corrections).map { ShelfEntry.File(it) }
    }
    val led = com.tmplayer.data.VideoSorting.sort(
        arranged.map { entry ->
            val film = (entry as? ShelfEntry.File)?.item?.takeIf { it.versions.size > 1 } ?: return@map entry
            val started = film.versions.firstOrNull { SettingsStore.progressKey(it.chatId, it.messageId) in watchProgress }
            if (started == null) entry else ShelfEntry.File(started.asVersion(film))
        },
        sort,
        progress = progressOf,
    )
    val shown = com.tmplayer.data.VideoFiltering.filter(led, filter, progressOf)
    val posters = SeriesShelf.posterKeys(shown)
    return Shelved(shown, MediaMapper.repeatedCaptionLines(items), posters, total = led.size)
}

/**
 * A chat's grid of [feed]: posters spanning [columns] / [posterColumns], wide cards and the rest
 * [columns] / [wideColumns], a sponsored message the whole row. [card] draws each entry; the promo
 * fixture calls this with the same cards, so its shots are of the real grid.
 */
internal fun LazyGridScope.shelfGrid(
    feed: List<MediaFeedEntry<ShelfEntry>>,
    posterKeys: Set<String>,
    columns: Int,
    posterColumns: Int,
    wideColumns: Int,
    /** Moves cards to their new places and fades new ones in ([animateItem]): a phone's, after a sort or a search. */
    animate: Boolean = false,
    card: @Composable (MediaFeedEntry<ShelfEntry>) -> Unit,
) {
    gridItems(
        items = feed,
        key = {
            when (it) {
                is MediaFeedEntry.Media -> it.item.key
                is MediaFeedEntry.Sponsored -> "sponsor-${it.item.messageId}"
            }
        },
        contentType = { feedContentType(it, posterKeys) },
        span = {
            when {
                it is MediaFeedEntry.Sponsored -> GridItemSpan(maxLineSpan)
                (it as MediaFeedEntry.Media).item.key in posterKeys -> GridItemSpan(columns / posterColumns)
                else -> GridItemSpan(columns / wideColumns)
            }
        },
    ) { entry ->
        if (animate) {
            Box(Modifier.animateItem(), propagateMinConstraints = true) { card(entry) }
        } else {
            card(entry)
        }
    }
}

/**
 * The filter in force over a chat's listing, on the phone and the TV: "Showing Unwatched: 3 of 40"
 * with Clear filter. When the filter hides everything loaded so far, it says that in its place
 * ("Unwatched hides all 40 videos loaded so far."), so an empty grid is never taken for an empty
 * chat; the pages go on loading under it, and each one is screened as it comes.
 */
@Composable
internal fun FilterNote(label: String, shown: Int, total: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(
        modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (shown == 0 && total > 0) s.gridFilterHidesAll(total, label) else s.gridFilterShowing(label, shown, total),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.weight(1f, fill = false),
        )
        com.tmplayer.ui.components.TmSecondaryButton(onClick = onClear) { Text(s.gridFilterClear) }
    }
}

/** What a feed's cell is drawn as, so the lazy layout reuses a poster for a poster, a show for a show. */
private fun feedContentType(entry: MediaFeedEntry<ShelfEntry>, posterKeys: Set<String>): Int = when (entry) {
    is MediaFeedEntry.Sponsored -> 0
    is MediaFeedEntry.Media -> when {
        entry.item is ShelfEntry.Show -> 1
        entry.item.key in posterKeys -> 2
        else -> 3
    }
}
