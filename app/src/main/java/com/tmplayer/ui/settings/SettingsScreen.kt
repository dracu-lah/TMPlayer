package com.tmplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CrashReports
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.WatchCache
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.release
import com.tmplayer.data.updateScheduler
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.player.AudioDownmix
import com.tmplayer.player.SubtitlePosition
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.TouchPrefs
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.update.LinkQrDialog
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.update.openLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pages Settings is split into, each opened from a row of the category list. Named in code
 * rather than by index, so a caller can open one directly and get back to it after About or the
 * cached videos. [Gestures] is a phone's alone; a television never shows its row.
 */
enum class SettingsPage { Appearance, Playback, Subtitles, Gestures, Library, Storage, About }

/**
 * Focus for a row in a lazy list, which exists only once the list has been measured: a frame
 * first, and a couple more if the row is still not there to take it.
 */
private suspend fun FocusRequester.settle() {
    repeat(3) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

private sealed interface Prompt {
    /** The one video watching left behind. */
    data object ClearCache : Prompt

    /** Everything else TMPlayer is holding: previews, thumbnails and the database. */
    data object ClearOther : Prompt

    /** Cache and pictures together, in one press. */
    data object ClearAllButDownloads : Prompt
    data object ClearHistory : Prompt
    data object ClearWatched : Prompt
    data object ClearFavorites : Prompt
    data object SignOut : Prompt
}

/**
 * Below this, what a clear would return is scraps and the wording should not promise otherwise.
 */
private const val CLEARING_WORTH_ASKING = CacheShelf.WORTH_ASKING_BYTES

/**
 * How many lines a row's description may take on a phone. Two cut the longer ones off mid
 * sentence ("Nothing is downloaded or installed without ..."), and on a portrait phone a switch
 * beside the text leaves it barely half the width. Four holds every English one with room for a
 * translation that runs longer.
 */
private const val SUPPORTING_LINES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    chats: List<ChatSummary>,
    onLoggedOut: () -> Unit,
    /** Leaving Settings. On a phone this is the app bar's arrow as well as the hardware key. */
    onBack: () -> Unit = {},
    /** The Cached videos row: the list of them, with Save to Downloads and Delete on each. */
    onOpenCachedVideos: () -> Unit = {},
    /** Settings, then About: the licence, the notices and every link. */
    onOpenAbout: () -> Unit = {},
    /**
     * The page to open on, or null for the category list. Also how a caller brings the viewer back
     * to the page they left for About or the cached videos, since this screen is gone meanwhile.
     */
    initialPage: SettingsPage? = null,
    /** Told every time a page opens or closes, so the caller can hand it back as [initialPage]. */
    onPageChange: (SettingsPage?) -> Unit = {},
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    // Matches the stored default, so the switch does not show as on for the frame before the
    // first value arrives and then visibly flick off.
    val openLastChat by settings.openLastChat.collectAsStateWithLifecycle(initialValue = false)
    // The single video watching leaves behind. Empty until the first read, which reads the same as
    // having none: "Nothing cached" for a frame is honest either way.
    val cached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadFirst by settings.downloadBeforePlaying.collectAsStateWithLifecycle(initialValue = false)
    val autoplayNext by settings.autoplayNext.collectAsStateWithLifecycle(initialValue = true)
    val detailFirst by settings.detailFirst.collectAsStateWithLifecycle(initialValue = true)
    val wifiOnly by settings.wifiOnlyDownloads.collectAsStateWithLifecycle(initialValue = false)
    val trickplayOn by settings.trickplay.collectAsStateWithLifecycle(initialValue = true)
    val watchNextOn by settings.watchNext.collectAsStateWithLifecycle(initialValue = false)
    val trickplayMemory = remember { DeviceQuirks.trickplayMemory(context) }
    val watchNextSupported = remember { DeviceQuirks.remote(context).watchNextSupported }
    val touchPrefs by settings.touchPrefs.collectAsStateWithLifecycle(initialValue = TouchPrefs())
    val downmixChoice by settings.downmixChoice.collectAsStateWithLifecycle(initialValue = null)
    val subtitleStyle by settings.subtitleStyle.collectAsStateWithLifecycle(initialValue = SubtitleStyle())
    val history by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchedStore = remember { WatchedStore(context) }
    val watchedList by watchedStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val lastChatId by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val minSize by settings.minSizeBytes.collectAsStateWithLifecycle(
        initialValue = SizeFilter.DEFAULT_MIN,
    )
    val maxSize by settings.maxSizeBytes.collectAsStateWithLifecycle(
        initialValue = SizeFilter.DEFAULT_MAX,
    )
    // Both only reach the screen on a phone, but they are read here with the rest so the Appearance
    // rows are plain state readers like every other row rather than each holding a collector.
    val themeChoice by settings.themeChoice.collectAsStateWithLifecycle(
        initialValue = ThemeChoice.Default,
    )
    val dynamicColour by settings.dynamicColour.collectAsStateWithLifecycle(initialValue = false)
    val crashReports by settings.crashReports.collectAsStateWithLifecycle(initialValue = false)

    val toast = rememberToast()
    val updateState by Updates.state.collectAsStateWithLifecycle()
    val updateNotify by settings.updateNotify.collectAsStateWithLifecycle(initialValue = true)
    var showUpdate by remember { mutableStateOf(false) }
    // A link a TV cannot open, shown as a QR code instead: the address and what it is.
    var qrLink by remember { mutableStateOf<Pair<String, String>?>(null) }
    var supporting by remember { mutableStateOf(false) }
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    // The language picker, the "What's new" sheet and "Report a problem", each over the list.
    var pickingLanguage by remember { mutableStateOf(false) }
    var stylingSubtitles by remember { mutableStateOf(false) }
    var editingSizes by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var onlineDialog by remember { mutableStateOf<OnlineDialog?>(null) }
    var editingTmdbKey by remember { mutableStateOf(false) }
    val language by settings.language.collectAsStateWithLifecycle(initialValue = "")
    val openPage = { url: String, what: String -> if (!openLink(context, url)) qrLink = url to what }
    // What TMPlayer is holding, split into downloads, cache and everything else. Worked out by
    // [StorageSplit], which is also what the Downloads screen reads, so the two panels cannot
    // quote different numbers for the same disk.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    // Filled by the first refresh, never read during composition: reading it is a blocking
    // statvfs().
    var disk by remember { mutableStateOf(DiskInfo.EMPTY) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    // The sign out dialog's tick box. Off every time the dialog opens: downloads are the viewer's
    // files, and losing them has to be asked for each time rather than remembered.
    var alsoDeleteDownloads by remember { mutableStateOf(false) }
    // Which end of the range the D-pad is currently moving.
    var editingUpper by remember { mutableStateOf(false) }
    // Where focus is sent back to after a reset of the size limits.
    val rangeRow = remember { FocusRequester() }
    // Where focus lands on a TV when a page opens: on every page a row that Left and Right cannot
    // change. On Appearance that is Language, not the Theme stepper above it, which once turned a
    // tester's TV to Light with a stray Left before they had even seen the screen.
    val landing = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // The page open over the category list, or null for the list itself. Saved, so a rotation
    // keeps the viewer where they were.
    var page by rememberSaveable { mutableStateOf(initialPage) }
    // A fresh scroll position for every page opened.
    val pageState = remember(page) { LazyListState() }
    // One per category row, so Back from a page can put focus on the row that opened it.
    val categoryFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    // The page just closed, for the list's focus to go back to; null on a first arrival.
    var returnTo by remember { mutableStateOf(initialPage) }
    val showPage = { target: SettingsPage ->
        page = target
        onPageChange(target)
    }
    val closePage = {
        returnTo = page
        page = null
        onPageChange(null)
    }
    fun titleOf(target: SettingsPage): String = when (target) {
        SettingsPage.Appearance -> s.settingsPageAppearance
        SettingsPage.Playback -> s.settingsPlayback
        SettingsPage.Subtitles -> s.settingsSubtitles
        SettingsPage.Gestures -> s.settingsPageGestures
        SettingsPage.Library -> s.settingsLibrary
        SettingsPage.Storage -> s.settingsPageStorage
        SettingsPage.About -> s.settingsPageAbout
    }

    // Back on a page goes to the list, not out of Settings. Composed after the caller's own
    // handler, so it is the one asked first while a page is open.
    BackHandler(enabled = page != null) { closePage() }

    suspend fun refresh() {
        // A TDLib round trip per record and a walk of the files directory, kept off the thread
        // drawing the list: this screen scrolls while it is counting.
        //
        // IO rather than Default. None of this is arithmetic, and Default is sized to the number
        // of cores, which on the stick this app is built for is two. Parking both of them on a
        // disk walk starves everything else scheduled there, including the preference reads this
        // same screen is drawn from.
        val measured = withContext(Dispatchers.IO) {
            val readDisk = DiskSpace.read(context)
            val readSplit = runCatching { StorageSplit.measure(context) }
                .getOrDefault(StorageSplit.EMPTY)
            readDisk to readSplit
        }
        disk = measured.first
        split = measured.second
    }

    val touch = isTouch()
    // These rows describe the machine they are running on, and half of them name it.
    val device = if (touch) "phone" else "tv"

    // The size beside the cached video has to follow the video: watching something else replaces
    // the record, and a figure left over from the last video is worse than no figure at all.
    LaunchedEffect(cached.map { it.fileId }) { refresh() }

    LaunchedEffect(Unit) { refresh() }

    // Nothing on a phone is driven by focus, and stealing it there would scroll the list to a
    // control the viewer never asked for.
    if (!touch) {
        LaunchedEffect(page) {
            val open = page
            if (open != null) {
                landing.settle()
                // Focus asks to be brought into view with room to spare, which can push the
                // heading off the top; once it has settled, the page goes back to its top.
                withFrameNanos { }
                pageState.scrollToItem(0)
            } else {
                val back = returnTo
                returnTo = null
                // Back from a page lands on the row that opened it, with the list scrolled where
                // it was. A first arrival starts on the first row, at the top.
                categoryFocus.getValue(back ?: SettingsPage.Appearance).settle()
                if (back == null) {
                    withFrameNanos { }
                    listState.scrollToItem(0)
                }
            }
        }
    }

    // Null until the chat list has loaded, which on a cold start into Settings it may not have.
    // The wording below covers both cases.
    val lastChatTitle = remember(chats, lastChatId) {
        chats.firstOrNull { it.id == lastChatId }?.title
    }

    // Overscan is a television's problem: a phone's are the status bar, the gesture handle and,
    // in landscape, a notch down one side. The list itself still runs the full height, so content
    // scrolls under the bars rather than stopping short of them.
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    val list: @Composable () -> Unit = {
    LazyColumn(
        // The category list keeps one state for as long as Settings is open, so coming back from
        // a page finds it scrolled where it was left. Each page starts at its own top.
        state = if (page == null) listState else pageState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (touch) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (touch) PhonePad.Side else Tv.SafeH),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            // The app bar already clears the status bar on a phone, so the list only owes the
            // gesture bar at the bottom.
            top = if (touch) 4.dp else Tv.SafeV,
            bottom = if (touch) insets.calculateBottomPadding() + 32.dp else 40.dp,
        ),
        // The gap between two rows of a phone's preference list is a divider's worth of nothing,
        // not a card margin.
        verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 10.dp),
    ) {
        // TV only: the phone's app bar already says where the viewer is and offers the way out.
        if (!touch) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(
                        page?.let(::titleOf) ?: s.settingsTitle,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Tone.text,
                    )
                    if (page == null) {
                        Text(
                            s.settingsSavesAsYouGo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                        )
                    }
                }
            }
        }

        // Whatever is currently running, at the top rather than below the last row: a viewer who
        // has just pressed a row is not looking past the end of the list.
        busy?.let { message ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = Tone.accent)
                }
            }
        }

        // Each page below holds what used to be one or more sections of a single long list. A
        // page that took in more than one keeps the old headings between them.
        when (page) {
            SettingsPage.Appearance -> {
                // First, on both devices: a stick often lives on a screen in a bright room, so the theme
                // is worth offering there too.
                item {
                    if (touch) {
                        ThemePicker(
                            current = themeChoice,
                            onPick = { scope.launch { settings.setThemeChoice(it) } },
                        )
                    } else {
                        // Segments are a thumb control and cannot be reached with a D-pad. The stepper is
                        // the same one the size limits use, so Left and Right already mean "change this".
                        StepperRow(
                            title = s.settingsTheme,
                            subtitle = themeChoice.tvDescription,
                            value = themeChoice.label,
                            icon = TmIcons.Theme,
                            canDecrease = themeChoice.ordinal > 0,
                            canIncrease = themeChoice.ordinal < ThemeChoice.entries.lastIndex,
                            onStep = { direction ->
                                val next = ThemeChoice.entries.getOrNull(themeChoice.ordinal + direction)
                                if (next != null) scope.launch { settings.setThemeChoice(next) }
                            },
                        )
                    }
                }

                // The language, under the theme: both are how the app looks before anything it shows.
                item {
                    ActionRow(
                        title = s.settingsLanguage,
                        subtitle = s.languageRowDetail(language),
                        icon = TmIcons.Language,
                        modifier = Modifier.focusRequester(landing),
                        onClick = { pickingLanguage = true },
                    )
                }

                if (touch) {
                    // Android 11 and earlier has no wallpaper palette to read, so the row would be a
                    // switch that changes nothing.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item {
                            ToggleRow(
                                title = s.settingsWallpaperColours,
                                subtitle = if (dynamicColour) {
                                    s.settingsWallpaperColoursOn
                                } else {
                                    s.settingsWallpaperColoursOff
                                },
                                icon = TmIcons.Palette,
                                checked = dynamicColour,
                                onToggle = { scope.launch { settings.setDynamicColour(!dynamicColour) } },
                            )
                        }
                    }
                }
            }

            SettingsPage.Playback -> {
                item {
                    ToggleRow(
                        title = s.settingsAutoplay,
                        modifier = Modifier.focusRequester(landing),
                        subtitle = if (autoplayNext) {
                            s.settingsAutoplayOn
                        } else {
                            s.settingsAutoplayOff
                        },
                        icon = Icons.Filled.PlayArrow,
                        checked = autoplayNext,
                        onToggle = { scope.launch { settings.setAutoplayNext(!autoplayNext) } },
                    )
                }
                item {
                    ToggleRow(
                        title = s.settingsDetailFirst,
                        subtitle = if (detailFirst) s.settingsDetailFirstOn else s.settingsDetailFirstOff,
                        icon = Icons.Filled.Info,
                        checked = detailFirst,
                        onToggle = { scope.launch { settings.setDetailFirst(!detailFirst) } },
                    )
                }
                item {
                    ToggleRow(
                        title = s.settingsDownloadFirst,
                        subtitle = if (downloadFirst) {
                            s.settingsDownloadFirstOn
                        } else {
                            s.settingsDownloadFirstOff
                        },
                        icon = TmIcons.Download,
                        checked = downloadFirst,
                        onToggle = {
                            scope.launch { settings.setDownloadBeforePlaying(!downloadFirst) }
                        },
                    )
                }
                // On both devices, defaulting the way each always has: a phone folds surround to stereo
                // for its speaker and headphones, a television passes it to whatever is wired up. The
                // switch is for the exceptions, a soundbar that cannot take the film's track or a phone
                // plugged into a receiver. Read when a video opens, so it applies from the next one.
                item {
                    val downmix = AudioDownmix.wanted(downmixChoice, television = !touch)
                    ToggleRow(
                        title = s.settingsDownmix,
                        subtitle = if (downmix) {
                            s.settingsDownmixOn
                        } else {
                            s.settingsDownmixOff
                        },
                        icon = ImageVector.vectorResource(R.drawable.ic_audio_language),
                        checked = downmix,
                        onToggle = { scope.launch { settings.setDownmix(!downmix) } },
                    )
                }
                // Thumbnails over the scrub bar, from what is already downloaded. Shown even where the
                // memory rules them out, switched off and saying why, so a 1 GB stick's owner who read
                // about the feature finds out it is not for this device rather than looking for it.
                item {
                    val on = trickplayOn && trickplayMemory
                    ToggleRow(
                        title = s.settingsTrickplay,
                        subtitle = when {
                            !trickplayMemory -> s.settingsTrickplayLowMemory
                            on -> s.settingsTrickplayOn
                            else -> s.settingsTrickplayOff
                        },
                        icon = TmIcons.Frames,
                        checked = on,
                        onToggle = {
                            if (trickplayMemory) scope.launch { settings.setTrickplay(!trickplayOn) }
                        },
                    )
                }
                // The Android TV home screen's "Play next" row. Only where there is one: a phone, a Fire TV
                // and a Fire tablet have none, and a switch for it there would do nothing.
                if (watchNextSupported) {
                    item {
                        ToggleRow(
                            title = s.settingsWatchNext,
                            subtitle = if (watchNextOn) s.settingsWatchNextOn else s.settingsWatchNextOff,
                            icon = Icons.Filled.Home,
                            checked = watchNextOn,
                            onToggle = {
                                val turningOff = watchNextOn
                                scope.launch {
                                    settings.setWatchNext(!turningOff)
                                    // Off means off: what was already put on the home screen comes back out.
                                    if (turningOff) {
                                        withContext(Dispatchers.IO) { runCatching { WatchNextPublisher.clearAll(context) } }
                                    }
                                }
                            },
                        )
                    }
                }
                // The one player timing that means the same with a remote as with a thumb.
                item {
                    val choices = TouchPrefs.TIMEOUT_CHOICES_MS
                    StepperRow(
                        title = s.settingsControlsTimeout,
                        subtitle = s.settingsControlsTimeoutDetail,
                        value = TouchPrefs.timeoutLabel(touchPrefs.controlsTimeoutMs),
                        icon = TmIcons.VisibilityOff,
                        canDecrease = touchPrefs.controlsTimeoutMs != choices.first(),
                        canIncrease = touchPrefs.controlsTimeoutMs != choices.last(),
                        onStep = { direction ->
                            scope.launch {
                                settings.updateTouchPrefs {
                                    it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, direction))
                                }
                            }
                        },
                    )
                }
            }

            SettingsPage.Subtitles -> {
                // The same three the player's subtitle list offers, with a preview here because nothing is
                // playing behind Settings to show the change on.

                item {
                    // The preview and the three choices open over the page (SubtitleStyleDialog); the row
                    // says what is set, so it can be read without opening it.
                    ActionRow(
                        title = s.settingsSubtitleStyle,
                        modifier = Modifier.focusRequester(landing),
                        subtitle = listOf(
                            subtitleStyle.size.label,
                            if (subtitleStyle.box) s.settingsSubtitleStyleBox else s.settingsSubtitleStyleNoBox,
                            subtitleStyle.position.label,
                        ).joinToString("  ·  "),
                        icon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                        onClick = { stylingSubtitles = true },
                    )
                }

                // ---- online subtitles ------------------------------------------------------------------
                // Only in a build that carries the OpenSubtitles key; a fork or a CI build has none.
                if (OnlineSubtitles.available) onlineSubtitlesSection(onDialog = { onlineDialog = it })
            }

            SettingsPage.Gestures -> {
                // Touch only: a remote has buttons for every one of these, and its key model is settled.
                if (touch) {
                    fun update(change: (TouchPrefs) -> TouchPrefs) {
                        scope.launch { settings.updateTouchPrefs(change) }
                    }
                    item {
                        ToggleRow(
                            title = s.settingsTapPlays,
                            subtitle = if (touchPrefs.tapPlaysPauses) {
                                s.settingsTapPlaysOn
                            } else {
                                s.settingsTapPlaysOff
                            },
                            icon = TmIcons.Pause,
                            checked = touchPrefs.tapPlaysPauses,
                            onToggle = { update { it.copy(tapPlaysPauses = !it.tapPlaysPauses) } },
                        )
                    }
                    item {
                        val choices = TouchPrefs.DOUBLE_TAP_CHOICES_MS
                        StepperRow(
                            title = s.settingsDoubleTap,
                            subtitle = s.settingsDoubleTapDetail,
                            value = s.settingsDoubleTapSeconds(touchPrefs.doubleTapMs / 1000),
                            icon = ImageVector.vectorResource(R.drawable.ic_player_forward),
                            canDecrease = touchPrefs.doubleTapMs != choices.first(),
                            canIncrease = touchPrefs.doubleTapMs != choices.last(),
                            onStep = { direction ->
                                update { it.copy(doubleTapMs = TouchPrefs.step(choices, it.doubleTapMs, direction)) }
                            },
                        )
                    }
                    item {
                        val choices = TouchPrefs.HOLD_CHOICES
                        StepperRow(
                            title = s.settingsHoldSpeed,
                            subtitle = s.settingsHoldSpeedDetail,
                            value = TouchPrefs.holdLabel(touchPrefs.holdSpeed),
                            icon = ImageVector.vectorResource(R.drawable.ic_speed),
                            canDecrease = touchPrefs.holdSpeed != choices.first(),
                            canIncrease = touchPrefs.holdSpeed != choices.last(),
                            onStep = { direction ->
                                update { it.copy(holdSpeed = TouchPrefs.step(choices, it.holdSpeed, direction)) }
                            },
                        )
                    }
                    item {
                        ToggleRow(
                            title = s.settingsSeekGesture,
                            subtitle = s.settingsSeekGestureDetail,
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            checked = touchPrefs.seekGesture,
                            onToggle = { update { it.copy(seekGesture = !it.seekGesture) } },
                        )
                    }
                    item {
                        ToggleRow(
                            title = s.settingsBrightnessGesture,
                            subtitle = s.settingsBrightnessGestureDetail,
                            icon = ImageVector.vectorResource(R.drawable.ic_brightness),
                            checked = touchPrefs.brightnessGesture,
                            onToggle = { update { it.copy(brightnessGesture = !it.brightnessGesture) } },
                        )
                    }
                    item {
                        ToggleRow(
                            title = s.settingsVolumeGesture,
                            subtitle = s.settingsVolumeGestureDetail,
                            icon = ImageVector.vectorResource(R.drawable.ic_volume),
                            checked = touchPrefs.volumeGesture,
                            onToggle = { update { it.copy(volumeGesture = !it.volumeGesture) } },
                        )
                    }
                    item {
                        ToggleRow(
                            title = s.settingsHaptics,
                            subtitle = s.settingsHapticsDetail,
                            icon = TmIcons.Vibration,
                            checked = touchPrefs.haptics,
                            onToggle = { update { it.copy(haptics = !it.haptics) } },
                        )
                    }
                }
            }

            SettingsPage.Library -> {
                // One row with the range in words; the slider, the note and Reset open over the page
                // (SizeLimitsDialog), so the block no longer pushes the rest of Settings down.

                item {
                    ActionRow(
                        title = s.settingsSizeLimits,
                        modifier = Modifier.focusRequester(landing),
                        subtitle = s.formatter.sizeRange(minSize, maxSize),
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { editingSizes = true },
                    )
                }

                // ---- startup ------------------------------------------------------------------------

                item { SectionTitle(s.settingsOnLaunch) }
                item {
                    ToggleRow(
                        title = s.settingsOpenLastChat,
                        subtitle = when {
                            lastChatTitle != null -> s.settingsOpenLastChatNamed(lastChatTitle)
                            lastChatId != 0L -> s.settingsOpenLastChatUnnamed
                            else -> s.settingsOpenLastChatOff
                        },
                        icon = TmIcons.History,
                        checked = openLastChat,
                        onToggle = { scope.launch { settings.setOpenLastChat(!openLastChat) } },
                    )
                }
                if (openLastChat && lastChatId != 0L) {
                    item {
                        ActionRow(
                            title = s.settingsForgetLastChat,
                            subtitle = s.settingsForgetLastChatDetail,
                            icon = Icons.Filled.Close,
                            // This row deletes itself on success, so it needs a toast: otherwise the
                            // press reads as having been lost.
                            onClick = {
                                scope.launch {
                                    settings.forgetLastChat()
                                    toast(s.settingsForgotLastChat)
                                }
                            },
                        )
                    }
                }

                // ---- lists ---------------------------------------------------------------------------

                // Every row below is conditional, so the heading only appears when it has something
                // under it rather than standing over empty space.
                if (favorites.isNotEmpty() || history.isNotEmpty() || watchedList.isNotEmpty()) {
                    item { SectionTitle(s.settingsLists) }
                }
                if (favorites.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearFavourites,
                            subtitle = s.settingsClearFavouritesDetail(favorites.size),
                            icon = Icons.Filled.Close,
                            onClick = { prompt = Prompt.ClearFavorites },
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearHistory,
                            subtitle = s.settingsClearHistoryDetail(history.size),
                            icon = Icons.Filled.Close,
                            onClick = { prompt = Prompt.ClearHistory },
                        )
                    }
                }
                if (watchedList.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearWatched,
                            subtitle = s.settingsClearWatchedDetail(watchedList.size),
                            icon = Icons.Filled.Close,
                            onClick = { prompt = Prompt.ClearWatched },
                        )
                    }
                }

                // ---- posters and details --------------------------------------------------------------
                onlineMetadataSection(onEditKey = { editingTmdbKey = true })
            }

            SettingsPage.Storage -> {
                item {
                    StorageCard(
                        split = split,
                        freeBytes = disk.freeBytes,
                        totalBytes = disk.totalBytes,
                    )
                }
                // The videos watching left behind, named and measured, each with Save to Downloads and
                // Delete: the row opens the list of them, which is the Downloads screen's third tab.
                item {
                    val held = cached.firstOrNull()
                    ActionRow(
                        title = s.settingsCachedVideos,
                        modifier = Modifier.focusRequester(landing),
                        subtitle = when {
                            split.cachedBytes <= 0 -> s.settingsCachedNone
                            // More than one means episodes an older version of the app left behind, so
                            // the count is quoted rather than claiming "1 video" beside two gigabytes.
                            split.cachedCount > 1 ->
                                s.settingsCachedCount(split.cachedCount, s.formatter.bytes(split.cachedBytes))
                            held != null -> s.settingsCachedOne(held.title, s.formatter.bytes(split.cachedBytes))
                            else -> s.formatter.bytes(split.cachedBytes)
                        },
                        icon = TmIcons.Download,
                        onClick = onOpenCachedVideos,
                    )
                }
                // The space back now, without choosing: the cache is one video and the next press of
                // Play replaces it anyway.
                item {
                    ActionRow(
                        title = s.settingsClearCache,
                        subtitle = s.settingsClearCacheDetail,
                        icon = Icons.Filled.Delete,
                        onClick = {
                            // Nothing to delete is not a dialog. The row stays focusable and in the same
                            // place every time, and says so instead.
                            if (split.cachedBytes <= 0) {
                                toast(s.settingsNothingCached)
                            } else {
                                prompt = Prompt.ClearCache
                            }
                        },
                    )
                }
                // Thumbnails, the database, the previews the browse screens keep. Always on the screen,
                // not only once there is a lot to reclaim: this is the only control for that space, and a
                // button that appears only on a full disk is one nobody can find on purpose.
                item {
                    ActionRow(
                        // Named for what it clears, beside the row above it that clears videos: two rows
                        // that both sound like the general answer to a full disk cannot be told apart.
                        title = s.settingsClearPictures,
                        subtitle = if (split.otherBytes <= 0) {
                            s.settingsClearPicturesNone
                        } else {
                            s.settingsClearPicturesDetail(s.formatter.bytes(split.otherBytes))
                        },
                        icon = Icons.Filled.Delete,
                        onClick = {
                            if (split.otherBytes <= 0) {
                                toast(s.settingsNothingToClear)
                            } else {
                                prompt = Prompt.ClearOther
                            }
                        },
                    )
                }
                // Everything above in a single press, for a full disk and no patience for two of them.
                // Downloads are not in it: they are the viewer's, and no button on this screen may take
                // one away. They are deleted from the Downloads screen instead.
                item {
                    ActionRow(
                        title = s.settingsClearAll,
                        subtitle = if (split.cachedBytes + split.otherBytes <= 0) {
                            s.settingsClearAllNone
                        } else {
                            s.settingsClearAllDetail(s.formatter.bytes(split.cachedBytes + split.otherBytes))
                        },
                        icon = Icons.Filled.Delete,
                        onClick = {
                            if (split.cachedBytes + split.otherBytes <= 0) {
                                toast(s.settingsNothingToClear)
                            } else {
                                prompt = Prompt.ClearAllButDownloads
                            }
                        },
                    )
                }
                // A phone's row only. A television is on the wall and a stick is behind it, both plugged
                // into a network that is Wi-Fi or a cable and never a data allowance somebody pays for by
                // the gigabyte, so on a TV this asks the viewer to rule out something that cannot happen.
                if (touch) {
                    item {
                        ToggleRow(
                            title = s.settingsWifiOnly,
                            subtitle = if (wifiOnly) {
                                s.settingsWifiOnlyOn
                            } else {
                                s.settingsWifiOnlyOff
                            },
                            icon = TmIcons.Wifi,
                            checked = wifiOnly,
                            onToggle = { scope.launch { settings.setWifiOnlyDownloads(!wifiOnly) } },
                        )
                    }
                }
            }

            SettingsPage.About -> {
                // ---- version -------------------------------------------------------------------------

                item { SectionTitle(s.settingsVersion) }
                updateState.release?.let { offered ->
                    item {
                        ActionRow(
                            title = UpdateWords.settingsRow(offered),
                            modifier = Modifier.focusRequester(landing),
                            subtitle = s.settingsUpdateDetail(s.formatter.bytes(Updates.apkFor(offered)?.size ?: 0L)),
                            icon = Icons.Filled.Refresh,
                            tint = Tone.caution,
                            onClick = { showUpdate = true },
                        )
                    }
                }
                item {
                    ActionRow(
                        title = s.settingsCheckUpdates,
                        modifier = if (updateState.release == null) Modifier.focusRequester(landing) else Modifier,
                        subtitle = s.settingsCheckUpdatesDetail(Updates.installedVersion, Updates.RELEASES_PAGE),
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            showUpdate = true
                            // Past the six hour wait and the skipped version: the viewer asked.
                            scope.launch { updateScheduler(context).checkNow() }
                        },
                    )
                }
                item {
                    ToggleRow(
                        title = s.settingsUpdateNotify,
                        subtitle = s.settingsUpdateNotifyDetail,
                        icon = TmIcons.Bell,
                        checked = updateNotify,
                        onToggle = { scope.launch { settings.setUpdateNotify(!updateNotify) } },
                    )
                }

                // ---- help ----------------------------------------------------------------------------

                item { SectionTitle(s.settingsHelp) }
                item {
                    ActionRow(
                        title = s.settingsWalkthrough,
                        subtitle = s.settingsWalkthroughBody,
                        icon = TmIcons.Help,
                        onClick = { scope.launch { settings.replayOverview() } },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsWhatsNew,
                        subtitle = s.settingsWhatsNewDetail(WhatsNew.VERSION),
                        icon = Icons.Filled.Star,
                        onClick = { whatsNew = true },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsChangelog,
                        subtitle = s.settingsChangelogDetail,
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { openPage(WhatsNew.CHANGELOG, s.settingsQrChangelogPage) },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsReportProblem,
                        subtitle = s.settingsReportProblemDetail,
                        icon = TmIcons.Bug,
                        onClick = { reporting = true },
                    )
                }
                item {
                    ActionRow(
                        title = s.aboutPrivacy,
                        subtitle = s.settingsPrivacyDetail(device),
                        icon = Icons.Filled.Lock,
                        onClick = { openPage(About.PRIVACY, s.settingsQrPrivacyPage) },
                    )
                }
                // The one setting that sends anything anywhere except Telegram and GitHub, and it is off
                // until it is asked for. A build with no DSN compiled in has nowhere to send a report, so
                // rather than offer a switch that does nothing, it is not drawn at all.
                if (CrashReports.available) {
                    item {
                        ToggleRow(
                            title = s.settingsCrashReports,
                            subtitle = if (crashReports) {
                                s.settingsCrashReportsOn
                            } else {
                                s.settingsCrashReportsOff
                            },
                            icon = Icons.Filled.Warning,
                            checked = crashReports,
                            onToggle = {
                                val next = !crashReports
                                scope.launch { settings.setCrashReports(next) }
                                if (next) CrashReports.start(context, true) else CrashReports.stop()
                            },
                        )
                    }
                }
                item {
                    ActionRow(
                        title = s.aboutLawfulUse,
                        subtitle = s.aboutLawfulUseDetail,
                        icon = TmIcons.Gavel,
                        onClick = { openPage(About.LEGAL, s.settingsQrLawfulUsePage) },
                    )
                }

                if (SupportReminder.enabled) {
                    item {
                        ActionRow(
                            title = if (supportCounters.supporter) About.SUPPORT_THANKS_TITLE else About.SUPPORT_TITLE,
                            subtitle = s.settingsSupportDetail,
                            icon = Icons.Filled.Favorite,
                            onClick = { supporting = true },
                        )
                    }
                }

                item {
                    ActionRow(
                        title = s.settingsAbout,
                        subtitle = s.settingsAboutDetail(Updates.installedVersion),
                        icon = Icons.Filled.Info,
                        onClick = onOpenAbout,
                    )
                }

                item {
                    Column(
                        Modifier.padding(
                            top = 20.dp,
                            start = if (touch) 16.dp else 0.dp,
                            end = if (touch) 16.dp else 0.dp,
                        ),
                    ) {
                        Text(
                            s.settingsPrivacyNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                        )
                    }
                }
            }

            // ---- the category list ---------------------------------------------------------------
            // One row per page: what the page is, and either the value that matters most in it or
            // a few words on what is inside.
            null -> {
                item {
                    ActionRow(
                        title = titleOf(SettingsPage.Appearance),
                        subtitle = s.settingsPageAppearanceSummary(
                            theme = themeChoice.label,
                            language = s.languageRowDetail(language),
                        ),
                        icon = TmIcons.Palette,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Appearance)),
                        onClick = { showPage(SettingsPage.Appearance) },
                    )
                }
                item {
                    ActionRow(
                        title = titleOf(SettingsPage.Playback),
                        subtitle = s.settingsPagePlaybackSummary,
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Playback)),
                        onClick = { showPage(SettingsPage.Playback) },
                    )
                }
                item {
                    val style = listOf(
                        subtitleStyle.size.label,
                        if (subtitleStyle.box) s.settingsSubtitleStyleBox else s.settingsSubtitleStyleNoBox,
                        subtitleStyle.position.label,
                    ).joinToString("  ·  ")
                    ActionRow(
                        title = titleOf(SettingsPage.Subtitles),
                        // The online half is only worth a mention in a build that has it.
                        subtitle = if (OnlineSubtitles.available) {
                            s.settingsPageSubtitlesSummaryOnline(style)
                        } else {
                            style
                        },
                        icon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Subtitles)),
                        onClick = { showPage(SettingsPage.Subtitles) },
                    )
                }
                // Touch only, like the rows inside it: a remote has a button for each of them.
                if (touch) {
                    item {
                        ActionRow(
                            title = titleOf(SettingsPage.Gestures),
                            subtitle = s.settingsPageGesturesSummary,
                            icon = ImageVector.vectorResource(R.drawable.ic_player_forward),
                            modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Gestures)),
                            onClick = { showPage(SettingsPage.Gestures) },
                        )
                    }
                }
                item {
                    ActionRow(
                        title = titleOf(SettingsPage.Library),
                        subtitle = s.settingsPageLibrarySummary,
                        icon = TmIcons.Grid,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Library)),
                        onClick = { showPage(SettingsPage.Library) },
                    )
                }
                item {
                    ActionRow(
                        title = titleOf(SettingsPage.Storage),
                        // The free space once it has been measured, which is the number somebody
                        // opening this page is usually after.
                        subtitle = if (disk.totalBytes > 0) {
                            s.settingsStorageFree(s.formatter.bytes(disk.freeBytes), s.formatter.bytes(disk.totalBytes))
                        } else {
                            s.settingsPageStorageSummary
                        },
                        icon = TmIcons.Download,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Storage)),
                        onClick = { showPage(SettingsPage.Storage) },
                    )
                }
                item {
                    // An update waiting is said here as well as inside, in the same colour, so it
                    // is not hidden one level down.
                    val offered = updateState.release
                    ActionRow(
                        title = titleOf(SettingsPage.About),
                        subtitle = if (offered != null) {
                            UpdateWords.settingsRow(offered)
                        } else {
                            s.settingsPageAboutSummary(Updates.installedVersion)
                        },
                        icon = Icons.Filled.Info,
                        tint = if (offered != null) Tone.caution else Color.Unspecified,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.About)),
                        onClick = { showPage(SettingsPage.About) },
                    )
                }

                // A page of its own would hold this one row, so it stays on the list, last.
                item { SectionTitle(s.settingsAccount) }
                item {
                    ActionRow(
                        title = s.settingsSignOut,
                        subtitle = s.settingsSignOutDetail(device),
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = { prompt = Prompt.SignOut },
                    )
                }
            }
        }
    }
    }

    if (touch) {
        // The phone needs an app bar: a heading that scrolls away with the list leaves no way off
        // this screen but the hardware Back. On a page the arrow goes back to the list, the same
        // as the hardware key.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { M3Text(page?.let(::titleOf) ?: s.settingsTitle) },
                    navigationIcon = {
                        IconButton(onClick = { if (page != null) closePage() else onBack() }) {
                            M3Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (page != null) {
                                    s.aboutBackToSettings
                                } else {
                                    s.settingsBackToChats
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { list() }
        }
    } else {
        list()
    }

    qrLink?.let { (url, what) -> LinkQrDialog(url = url, what = what, onClose = { qrLink = null }) }
    if (supporting) SupportDialog(from = "settings", onClose = { supporting = false })
    if (pickingLanguage) LanguageDialog(settings, onClose = { pickingLanguage = false })
    if (stylingSubtitles) {
        SubtitleStyleDialog(
            style = subtitleStyle,
            onChange = { next -> scope.launch { settings.setSubtitleStyle(next) } },
            onClose = { stylingSubtitles = false },
        )
    }
    if (editingSizes) {
        SizeLimitsDialog(
            minSize = minSize,
            maxSize = maxSize,
            editingUpper = editingUpper,
            rangeRow = rangeRow,
            onSwitchEnd = { editingUpper = !editingUpper },
            onStep = { upper, direction ->
                scope.launch {
                    if (upper) {
                        settings.setMaxSizeBytes(SizeFilter.step(maxSize, direction))
                    } else {
                        settings.setMinSizeBytes(SizeFilter.step(minSize, direction))
                    }
                }
            },
            onSetRange = { low, high ->
                scope.launch {
                    // Widen first: the clamp that stops the ends crossing would otherwise refuse
                    // a new floor past the old ceiling.
                    settings.setMaxSizeBytes(high)
                    settings.setMinSizeBytes(low)
                }
            },
            onClose = { editingSizes = false },
        )
    }
    if (whatsNew) {
        WhatsNewDialog(
            onClose = { whatsNew = false },
            onChangelog = {
                whatsNew = false
                openPage(WhatsNew.CHANGELOG, s.settingsQrChangelogPage)
            },
        )
    }
    if (reporting) FeedbackDialog(onClose = { reporting = false })
    onlineDialog?.let { OnlineSubtitlesDialog(it, onClose = { onlineDialog = null }) }
    if (editingTmdbKey) MetadataKeyDialog(onClose = { editingTmdbKey = false })

    when (prompt) {
        Prompt.ClearCache -> TvConfirm(
            title = s.settingsClearCacheTitle,
            message = s.settingsClearCacheMessage(split.cachedCount, s.formatter.bytes(split.cachedBytes)),
            // Says what it will not touch, so it cannot be mistaken for the clear-everything row
            // a little further down.
            detail = s.settingsDownloadsUntouched,
            confirmLabel = s.commonClear,
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = s.settingsClearing
                    val freed = split.cachedBytes
                    runCatching { WatchCache.clearAll(context) }
                    refresh()
                    busy = null
                    toast(s.settingsFreed(s.formatter.bytes(freed)))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearOther -> TvConfirm(
            title = s.settingsClearPicturesTitle,
            message = s.settingsClearPicturesMessage(s.formatter.bytes(split.otherBytes)),
            detail = s.settingsClearPicturesUntouched,
            confirmLabel = s.settingsClearPicturesConfirm,
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = s.settingsDeleting
                    val freed = split.otherBytes
                    // Pictures only. Nothing here may touch the download index or the videos on
                    // disk: this row is named for thumbnails.
                    runCatching { Td.clearPicturesAndPreviews() }
                    refresh()
                    busy = null
                    toast(s.settingsFreed(s.formatter.bytes(freed)))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearAllButDownloads -> TvConfirm(
            title = s.settingsClearAllTitle,
            message = s.settingsClearAllMessage(s.formatter.bytes(split.cachedBytes + split.otherBytes)),
            detail = s.settingsClearAllUntouched,
            confirmLabel = s.commonClear,
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = s.settingsClearing
                    val freed = split.cachedBytes + split.otherBytes
                    // The videos first, through the one thing that knows which of them are the
                    // viewer's downloads, and then the pictures, which cannot be anybody's.
                    runCatching { WatchCache.clearAll(context) }
                    runCatching { Td.clearPicturesAndPreviews() }
                    refresh()
                    busy = null
                    toast(s.settingsFreed(s.formatter.bytes(freed)))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearHistory -> TvConfirm(
            title = s.settingsClearHistoryTitle,
            message = s.settingsClearHistoryMessage,
            detail = s.settingsClearHistoryUntouched,
            confirmLabel = s.commonClear,
            onConfirm = {
                prompt = null
                scope.launch {
                    val count = history.size
                    settings.clearWatchHistory()
                    // What was cleared lives on the browse screen, not this one, so the toast is
                    // the only sign anything happened.
                    toast(s.settingsHistoryCleared(count))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearWatched -> TvConfirm(
            title = s.settingsClearWatchedTitle,
            message = s.settingsClearWatchedMessage,
            detail = s.settingsClearWatchedUntouched,
            confirmLabel = s.commonClear,
            onConfirm = {
                prompt = null
                scope.launch {
                    val count = watchedList.size
                    runCatching { watchedStore.clear() }
                    toast(s.settingsWatchedCleared(count))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearFavorites -> TvConfirm(
            title = s.settingsClearFavouritesTitle,
            message = s.settingsClearFavouritesMessage(favorites.size),
            detail = s.settingsClearFavouritesUntouched,
            confirmLabel = s.commonClear,
            onConfirm = {
                prompt = null
                scope.launch {
                    val count = favorites.size
                    settings.clearFavorites()
                    toast(s.settingsFavouritesCleared(count))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.SignOut -> TvConfirm(
            title = s.settingsSignOutTitle,
            message = s.settingsSignOutMessage,
            detail = if (split.downloadBytes > 0) s.settingsSignOutDownloadsStay else null,
            confirmLabel = s.settingsSignOutConfirm,
            extra = if (split.downloadBytes > 0) {
                {
                    TickRow(
                        label = s.settingsSignOutDeleteDownloads(s.formatter.bytes(split.downloadBytes)),
                        checked = alsoDeleteDownloads,
                        onToggle = { alsoDeleteDownloads = !alsoDeleteDownloads },
                    )
                }
            } else {
                null
            },
            onConfirm = {
                prompt = null
                val deleteDownloads = alsoDeleteDownloads
                alsoDeleteDownloads = false
                scope.launch {
                    busy = s.settingsSigningOut
                    // Everything this app knows about the account that is leaving goes: the
                    // cache and every preference. TDLib clears its own database as it logs out.
                    // The downloads are files in their own folder and stay, with their records,
                    // unless the box was ticked: they are the viewer's, not the account's.
                    runCatching { Td.clearMediaCache() }
                    if (deleteDownloads) {
                        withContext(Dispatchers.IO) { runCatching { LocalDownloads.deleteAll(settings) } }
                    }
                    // Consent goes out with the preferences, so the SDK is shut down here rather
                    // than left running until the next launch: somebody handing the television on
                    // must not leave a reporter switched on behind them.
                    runCatching { CrashReports.stop() }
                    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
                    // What this account watched is its own as much as the preferences are, and it
                    // lives in a file of its own, so it is cleared on its own.
                    runCatching { watchedStore.clear() }
                    Td.logOut()
                    onLoggedOut()
                }
            },
            onDismiss = {
                prompt = null
                alsoDeleteDownloads = false
            },
        )

        null -> Unit
    }

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false; Updates.dismiss() })
    }
}

/**
 * A slider a remote can actually drive: left and right nudge it, and the value is spelled out
 * rather than left to be guessed from the thumb position.
 *
 * Left/right are consumed here, so focus cannot escape sideways mid-adjustment. Settings is a
 * single vertical column, so nothing is lost by that.
 *
 * A phone sends no key events, so touch goes to [TouchRangeRow] instead.
 */
@Composable
private fun RangeRow(
    minValue: Long,
    maxValue: Long,
    editingUpper: Boolean,
    onSwitchEnd: () -> Unit,
    /** Move one end of the range: the upper one when `upper`, by one step in `direction`. */
    onStep: (upper: Boolean, direction: Int) -> Unit,
    /** Both ends at once, which is what a dragged range slider reports on a phone. */
    onSetRange: (min: Long, max: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    if (touch) {
        TouchRangeRow(minValue, maxValue, onSetRange, modifier)
        return
    }

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surface,
        animationSpec = tween(140),
        label = "rangeBackground",
    )
    val onSurface = if (focused) Tone.onFocusFill else Tone.text
    // 0.8 at the least: the second line has to stay legible against the accent, and anything
    // lighter drops under the 4.5:1 a small caption needs.
    val dim = if (focused) Tone.onFocusFill.copy(alpha = 0.8f) else Tone.muted

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Large))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Large))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(editingUpper, -1); true }
                    Key.DirectionRight -> { onStep(editingUpper, 1); true }
                    // OK swaps which end moves, so one control covers both ends.
                    Key.DirectionCenter, Key.Enter -> { onSwitchEnd(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            End(s.settingsRangeFrom, s.formatter.sizeLimit(minValue), active = !editingUpper, onSurface, dim, focused)
            Spacer(Modifier.weight(1f))
            End(s.settingsRangeUpTo, s.formatter.sizeLimit(maxValue), active = editingUpper, onSurface, dim, focused)
        }
        Spacer(Modifier.height(12.dp))
        RangeTrack(minValue, maxValue, editingUpper, focused)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The ends of the track say what the ends of the range would read as, so the track
            // and the "From" / "Up to" captions above it cannot disagree with each other.
            Text(
                s.formatter.sizeLimit(SizeFilter.FLOOR),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (focused) {
                    // Drawn icons rather than the \u25C0 \u25B6 characters: TV firmware fonts often have no
                    // glyph for those, and tofu boxes would be the whole explanation of the
                    // control.
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = Tone.onFocusFill,
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Tone.onFocusFill,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Named after the captions overhead rather than "upper" and "lower", so the
                    // hint and the labels match.
                    Text(
                        if (editingUpper) {
                            s.settingsRangeHint(s.settingsRangeUpTo, s.settingsRangeFrom)
                        } else {
                            s.settingsRangeHint(s.settingsRangeFrom, s.settingsRangeUpTo)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.onFocusFill,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                s.formatter.sizeLimit(SizeFilter.CEILING),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
        }
    }
}

/**
 * The same range, for a screen that is touched rather than pointed at.
 *
 * Both ends are draggable at once, so there is no live end to keep in mind. The track stays,
 * because it is the only thing that says how far apart the two numbers are.
 */
@Composable
private fun TouchRangeRow(
    minValue: Long,
    maxValue: Long,
    onSetRange: (min: Long, max: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Held locally while the thumb is down so the track follows the finger at frame rate; the
    // store is written once, when the finger lifts. Keyed on the stored values so a reset from
    // the chip above, or the first value arriving off disk, moves the thumbs.
    // The slider runs over track positions, 0..1, not bytes: the scale is not linear (see
    // SizeFilter.fraction), so 50 MB sits clearly to the right of "No minimum".
    var range by remember(minValue, maxValue) {
        mutableStateOf(SizeFilter.fraction(minValue)..SizeFilter.fraction(maxValue))
    }
    val low = SizeFilter.snap(SizeFilter.bytesAt(range.start))
    val high = SizeFilter.snap(SizeFilter.bytesAt(range.endInclusive))

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Tone.surface,
        shape = M3Theme.shapes.large,
    ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        // The two figures above the track, where the eye already is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RangeEnd(s.settingsRangeFrom, s.formatter.sizeLimit(low))
            Spacer(Modifier.weight(1f))
            RangeEnd(s.settingsRangeUpTo, s.formatter.sizeLimit(high))
        }
        Spacer(Modifier.height(4.dp))
        // Material's own control: dragging a range is what a phone is for, and it brings its own
        // accessibility, with TalkBack announcing each thumb's value and moving it.
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = {
                onSetRange(
                    SizeFilter.snap(SizeFilter.bytesAt(range.start)),
                    SizeFilter.snap(SizeFilter.bytesAt(range.endInclusive)),
                )
            },
            valueRange = 0f..1f,
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                s.formatter.sizeLimit(SizeFilter.FLOOR),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            // A mark at 1 GB, because the scale is not linear and the ends alone would let the
            // track pass for one. Placed by the same mapping the thumbs use.
            Text(
                s.formatter.sizeLimit(SizeFilter.GB),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(BiasAlignment.Horizontal(SizeFilter.fraction(SizeFilter.GB) * 2 - 1)),
            )
            Text(
                s.formatter.sizeLimit(SizeFilter.CEILING),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    }
}

/** One end's caption and figure, above the thumb it belongs to. */
@Composable
private fun RangeEnd(caption: String, value: String) {
    Column {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Tone.text)
    }
}

/** One end of the range. The active one is the only thing the D-pad will move. */
@Composable
private fun End(
    caption: String,
    value: String,
    active: Boolean,
    onSurface: Color,
    dim: Color,
    focused: Boolean,
) {
    Column {
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = dim)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (active) onSurface else dim,
        )
        // A rule under the live end, because on a TV a colour difference alone is easy to miss.
        Box(
            Modifier
                .padding(top = 3.dp)
                .height(3.dp)
                .width(if (active) 56.dp else 0.dp)
                .clip(CircleShape)
                .background(if (focused) Tone.onFocusFill else Tone.accent),
        )
    }
}

@Composable
private fun RangeTrack(minValue: Long, maxValue: Long, editingUpper: Boolean, focused: Boolean) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.onFocusFill.copy(alpha = 0.22f) else Tone.surfaceHigh),
    ) {
        val width = maxWidth
        val start = width * SizeFilter.fraction(minValue)
        val span = (width * (SizeFilter.fraction(maxValue) - SizeFilter.fraction(minValue)))
            .coerceAtLeast(3.dp)

        Box(
            Modifier
                .padding(start = start)
                .width(span)
                .fillMaxHeight()
                .background(if (focused) Tone.onFocusFill.copy(alpha = 0.55f) else Tone.accent.copy(alpha = 0.5f)),
        )

        Thumb(width, minValue, live = !editingUpper, focused = focused)
        Thumb(width, maxValue, live = editingUpper, focused = focused)
    }
}

@Composable
private fun BoxWithConstraintsScope.Thumb(width: Dp, value: Long, live: Boolean, focused: Boolean) {
    val w = if (live) 10.dp else 6.dp
    Box(
        Modifier
            .padding(start = (width * SizeFilter.fraction(value) - w / 2).coerceIn(0.dp, width - w))
            .width(w)
            .fillMaxHeight()
            .clip(CircleShape)
            .background(
                when {
                    focused && live -> Tone.onFocusFill
                    focused -> Tone.onFocusFill.copy(alpha = 0.7f)
                    live -> Tone.accent
                    else -> Tone.accent.copy(alpha = 0.6f)
                },
            ),
    )
}

/** Right-aligned "Reset", greyed out when the range is already the default. */
@Composable
private fun ResetChip(enabled: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    if (isTouch()) {
        // Material's own chip on a phone: height, ripple, disabled colours and outline for free.
        // The television cannot use it, because a chip has no focus appearance to speak of.
        AssistChip(
            onClick = onClick,
            enabled = enabled,
            label = { M3Text(s.commonReset, maxLines = 1) },
            leadingIcon = {
                M3Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        return
    }

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surfaceHigh,
        animationSpec = tween(140),
        label = "resetChip",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (enabled) background else Tone.surface)
            // Always clickable, never `enabled = false`: a disabled clickable installs no focus
            // target, so the node the viewer is standing on would vanish underneath them the
            // moment the press greys the chip out. Greyed out it simply does nothing.
            .clickable(
                interactionSource = interactions,
                // Focus colour is the whole of the feedback on a TV.
                indication = null,
                onClick = { if (enabled) onClick() },
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = when {
                !enabled -> Tone.muted
                focused -> Tone.onFocusFill
                else -> Tone.text
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            s.commonReset,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> Tone.muted
                focused -> Tone.onFocusFill
                else -> Tone.text
            },
            maxLines = 1,
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    val touch = isTouch()
    Text(
        text,
        style = sectionStyle(),
        // Accent on a phone, as every Android preference screen does: with the rows un-carded,
        // the colour is what separates one group from the next.
        color = if (touch) Tone.accent else Tone.text,
        modifier = if (touch) {
            // The 16dp start is the same inset a ListItem gives its headline, so the heading and
            // the rows under it begin on one line.
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        } else {
            Modifier.padding(top = 16.dp, bottom = 4.dp)
        },
    )
}

/**
 * The heading over a group of rows.
 *
 * Two steps down on a phone: at TV size a heading is nearly as loud as the screen's own, which in
 * a narrow column reads as a list of headings with the settings hidden between them. `titleSmall`
 * in the primary colour is the Material preference screen's own answer, where colour rather than
 * size marks a heading out.
 */
@Composable
private fun sectionStyle() = if (isTouch()) {
    MaterialTheme.typography.titleSmall
} else {
    MaterialTheme.typography.titleLarge
}

/**
 * Light, dark or whatever the phone is set to, as three segments rather than three rows.
 *
 * A chosen segment carries a check mark as well as its word, and a third of a phone's width is not
 * much room, so the segments say only "System", "Light" and "Dark". The sentence explaining the
 * chosen one sits under the row, where it has the whole width to itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePicker(current: ThemeChoice, onPick: (ThemeChoice) -> Unit) {
    val options = ThemeChoice.entries
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = choice == current,
                    onClick = { onPick(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    // The word alone is ambiguous read aloud, so the spoken label is the sentence.
                    modifier = Modifier.semantics { contentDescription = choice.description },
                    label = {
                        M3Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        M3Text(
            current.description,
            style = M3Theme.typography.bodyMedium,
            color = M3Theme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StorageCard(
    split: StorageSplit,
    freeBytes: Long,
    totalBytes: Long,
) {
    val s = LocalStrings.current
    val cacheBytes = split.totalBytes
    val used = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction = if (totalBytes > 0) used.toFloat() / totalBytes else 0f
    val cacheFraction = if (totalBytes > 0) cacheBytes.toFloat() / totalBytes else 0f
    // Until the first measurement lands there is nothing to divide, so the bar keeps its single
    // band and the lines below stay away rather than showing three zeroes.
    val measured = cacheBytes > 0

    val touch = isTouch()
    val bar = if (touch) 10.dp else 14.dp

    // Three roles of one palette on a phone, so the bands still read as three parts of the same
    // thing when the wallpaper decides that palette. A television has no scheme to draw from and
    // uses three alphas of the app's own green.
    val videoBand = if (touch) M3Theme.colorScheme.primary else Tone.accent
    val pictureBand = if (touch) M3Theme.colorScheme.secondary else Tone.accent.copy(alpha = 0.6f)
    val otherBand = if (touch) M3Theme.colorScheme.tertiary else Tone.accent.copy(alpha = 0.3f)
    val deviceBand = if (touch) Tone.outline else Tone.muted.copy(alpha = 0.6f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
        shape = if (touch) M3Theme.shapes.large else RoundedCornerShape(Corner.Large),
    ) {
    Column(Modifier.padding(if (touch) 16.dp else 20.dp)) {
        Text(
            s.settingsStorageFree(s.formatter.bytes(freeBytes), s.formatter.bytes(totalBytes)),
            style = if (touch) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = Tone.text,
        )
        Spacer(Modifier.height(if (touch) 12.dp else 16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(bar)
                .clip(CircleShape)
                .background(Tone.surfaceHigh)
                // The bar is three coloured boxes and nothing else, so it needs a description of
                // its own. Merged into a single node, because the parts mean nothing apart.
                .semantics(mergeDescendants = true) {
                    val f = s.formatter
                    contentDescription = if (measured) {
                        s.settingsStorageDescription(
                            f.bytes(used), f.bytes(totalBytes), f.bytes(cacheBytes),
                            f.bytes(split.downloadBytes), f.bytes(split.cachedBytes), f.bytes(split.otherBytes),
                        )
                    } else {
                        s.settingsStorageDescriptionMeasuring(f.bytes(used), f.bytes(totalBytes), f.bytes(cacheBytes))
                    }
                },
        ) {
            // Everything on the device, then TMPlayer's own slice highlighted inside it, and
            // inside that the three things the slice is made of.
            Box(
                Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .background(deviceBand),
            )
            Row(Modifier.fillMaxWidth(cacheFraction).fillMaxHeight()) {
                if (measured) {
                    // Weights, not fractions: these three divide TMPlayer's own band between
                    // them, and a band of zero width simply draws nothing.
                    StorageSlice(split.downloadBytes, videoBand)
                    StorageSlice(split.cachedBytes, pictureBand)
                    StorageSlice(split.otherBytes, otherBand)
                } else {
                    Box(Modifier.fillMaxSize().background(videoBand))
                }
            }
        }
        Spacer(Modifier.height(if (touch) 12.dp else 16.dp))
        Text(
            if (measured) {
                s.settingsStorageSplit(
                    s.formatter.bytes(split.downloadBytes),
                    s.formatter.bytes(split.cachedBytes),
                    s.formatter.bytes(split.otherBytes),
                )
            } else {
                s.settingsStorageUsing(s.formatter.bytes(cacheBytes))
            },
            style = if (touch) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = Tone.text,
        )
        if (measured) {
            Spacer(Modifier.height(8.dp))
            // The same three names, in the same order, as the panel at the top of the Downloads
            // screen: one measurement drawn twice, so the two must not disagree.
            if (split.downloadBytes > 0) {
                StorageLegend(s.settingsStorageDownloads, split.downloadBytes, videoBand)
            }
            if (split.cachedBytes > 0) {
                StorageLegend(s.settingsStorageCached, split.cachedBytes, pictureBand)
            }
            if (split.otherBytes > 0) {
                StorageLegend(s.settingsStoragePictures, split.otherBytes, otherBand)
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            // The two devices keep different bargains, so the wording has to name the one in
            // hand: a phone also holds downloads the viewer deletes themselves.
            if (touch) {
                s.settingsStorageNotePhone
            } else {
                s.settingsStorageNoteTv
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
    }
    }
}

/**
 * A tick box with its words, for a choice inside a dialog. The whole row is the target, so a
 * thumb need not find the box and a remote can land on it; on a television it wears the focus
 * ring the rest of the app's controls do.
 */
@Composable
private fun TickRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Small))
            .focusRing(focused, RoundedCornerShape(Corner.Small))
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        M3Text(label, style = M3Theme.typography.bodyMedium, color = Tone.text)
    }
}

/** One band of TMPlayer's own slice of the bar, sized by its share of the bytes. */
@Composable
private fun RowScope.StorageSlice(bytes: Long, color: Color) {
    if (bytes <= 0) return
    Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color))
}

/** The line naming one of those bands. Merged, so a screen reader reads the pair as a sentence. */
@Composable
private fun StorageLegend(label: String, bytes: Long, color: Color) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = s.settingsStorageLegend(label, s.formatter.bytes(bytes))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Spacer(Modifier.weight(1f))
        Text(
            s.formatter.bytes(bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.text,
        )
    }
}

/**
 * A row whose value is a number the viewer walks up and down.
 *
 * A phone gets two arrow buttons at the end of the row. A television has no second target to aim
 * at, so the row itself takes focus and the D-pad moves the number: the arrows are still drawn,
 * greyed at the ends, because they are what says the row can be moved at all.
 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    icon: ImageVector,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (direction: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val touch = isTouch()

    if (touch) {
        ListItem(
            headlineContent = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { M3Text(s.settingsValueAndDetail(value, subtitle), maxLines = 3) },
            leadingContent = {
                M3Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onStep(-1) }, enabled = canDecrease) {
                        M3Icon(Icons.Filled.KeyboardArrowDown, contentDescription = s.settingsStepDown)
                    }
                    IconButton(onClick = { onStep(1) }, enabled = canIncrease) {
                        M3Icon(Icons.Filled.KeyboardArrowUp, contentDescription = s.settingsStepUp)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        )
        return
    }

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surface,
        animationSpec = tween(140),
        label = "stepperBackground",
    )
    val onSurface = if (focused) Tone.onFocusFill else Tone.text
    // 0.8 at the least: the second line has to stay legible against the accent, and anything
    // lighter drops under the 4.5:1 a small caption needs.
    val dim = if (focused) Tone.onFocusFill.copy(alpha = 0.8f) else Tone.muted

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(RoundedCornerShape(Corner.Large))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Large))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Left and Right, not Up and Down: Up and Down move between settings, and Left
                // and Right lead nowhere in a single column, so they are the pair to spend.
                when (event.key) {
                    Key.DirectionLeft -> { if (canDecrease) onStep(-1); true }
                    Key.DirectionRight -> { if (canIncrease) onStep(1); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = onSurface, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (focused) s.settingsStepperHint(value) else s.settingsValueAndDetail(value, subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = if (canDecrease) onSurface else dim,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (canIncrease) onSurface else dim,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * A frame of picture with a line of subtitle on it, drawn the way [style] says.
 *
 * Sized off its own height the way the player sizes off the video's, so Large here is Large there
 * in proportion, whatever the screen.
 */
@Composable
private fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    BoxWithConstraints(
        modifier
            // The cap before the fill: the other way round the fill has already made the width
            // exact and the cap is ignored, which on a television drew a 16:9 frame the width of
            // the whole column, taller than the screen has room for beside the rows.
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(Corner.Large))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF12161C)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val density = LocalDensity.current
        val fontSize = with(density) { (maxHeight * style.size.fraction).toSp() }
        M3Text(
            s.settingsSubtitlePreview,
            color = Color.White,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 6f)),
            modifier = Modifier
                .padding(bottom = maxHeight * style.position.bottomFraction, start = 12.dp, end = 12.dp)
                .then(if (style.box) Modifier.background(Color.Black.copy(alpha = 0.75f)) else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** Unspecified leaves the icon the colour of the rest of the row. */
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val iconTint = if (tint == Color.Unspecified) Tone.text else tint
    FocusRow(
        modifier = modifier,
        onClick = onClick,
        headline = {
            M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supporting = {
            M3Text(subtitle, maxLines = SUPPORTING_LINES, overflow = TextOverflow.Ellipsis)
        },
        leading = {
            M3Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        },
    ) { focused ->
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Tone.onFocusFill else iconTint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Tone.onFocusFill else Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusRow(
        modifier = modifier,
        onClick = onToggle,
        headline = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = { M3Text(subtitle, maxLines = SUPPORTING_LINES, overflow = TextOverflow.Ellipsis) },
        leading = {
            M3Icon(
                icon,
                contentDescription = null,
                tint = Tone.text,
                modifier = Modifier.size(24.dp),
            )
        },
        // A real switch on a phone, not the drawn pill below: it can be dragged as well as
        // tapped, and TalkBack announces it as "switch, on" without being told.
        trailing = { M3Switch(checked = checked, onCheckedChange = { onToggle() }) },
    ) { focused ->
        // An icon for the same reason ActionRow has one: the two kinds of row are stacked in one
        // column, and without it their titles would start at two different x positions.
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Tone.onFocusFill else Tone.text,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Tone.onFocusFill else Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, focused = focused, touch = false)
    }
}

/**
 * A plain pill switch, big enough to read across a room.
 *
 * It is drawn rather than dispatched: the whole row is the control on both devices, so the pill
 * only ever reports the state. On a phone it comes down a size, because a room's worth of switch
 * beside a wrapped two-line title is the loudest thing in the list.
 */
@Composable
private fun Switch(checked: Boolean, focused: Boolean, touch: Boolean = false) {
    // The pill swaps which way round it is drawn depending on what is behind it. A focused row is
    // filled in, so on it the live track has to be that fill's own contrast colour; off the row
    // the accent itself is the live one. A fixed colour cannot read against both.
    val track by animateColorAsState(
        targetValue = when {
            checked -> if (focused) Tone.onFocusFill else Tone.accent
            focused -> Tone.onFocusFill.copy(alpha = 0.35f)
            else -> Tone.surfaceHigh
        },
        animationSpec = tween(140),
        label = "switchTrack",
    )
    val height = if (touch) 30.dp else 34.dp
    val knob = if (touch) 22.dp else 26.dp
    Box(
        Modifier
            .size(width = if (touch) 52.dp else 64.dp, height = height)
            .clip(CircleShape)
            .background(track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(knob)
                .clip(CircleShape)
                .background(
                    when {
                        // The row's own fill, which is the one colour guaranteed to read against
                        // the track: the track on a focused row is that fill's contrast colour.
                        focused -> Tone.focusFill
                        checked -> Tone.onFocusFill
                        else -> Tone.surface
                    },
                ),
        )
    }
}

/**
 * One row of the list, on either machine.
 *
 * A phone gets Material's own `ListItem`, which owns the text styles, insets and heights every
 * other Android settings screen is built from. A television cannot use it, because a focused row
 * there has to fill with colour and take its text white with it. So the slots are drawn on the
 * phone and [content] on the TV.
 */
@Composable
private fun FocusRow(
    modifier: Modifier,
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    supporting: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surface,
        animationSpec = tween(140),
        label = "rowBackground",
    )

    val touch = isTouch()

    if (touch) {
        ListItem(
            headlineContent = headline,
            supportingContent = supporting,
            leadingContent = leading,
            trailingContent = trailing,
            // Transparent, so the rows read as a list rather than a stack of panels: the screen's
            // own background is what shows between them.
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(
                    interactionSource = interactions,
                    indication = LocalIndication.current,
                    // Named as a button, so a screen reader offers "double tap to activate".
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
        return
    }

    Row(
        modifier
            .fillMaxWidth()
            // One line of subtitle at this height in English, which is most rows. A longer
            // translation takes a second line and the row grows to fit, rather than cutting the
            // sentence off at the ellipsis.
            .heightIn(min = 66.dp)
            // The filled card is how the focused row is found from a sofa.
            .clip(RoundedCornerShape(Corner.Large))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Large))
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}

/**
 * Settings, Subtitle style: the preview at the top, where the change shows the moment it is made,
 * and under it the three choices as the pickers' radio groups (size, background box, position).
 * Each choice is saved as it is picked, and the dialog stays open for the next one.
 */
@Composable
private fun SubtitleStyleDialog(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val first = remember { FocusRequester() }
    ChoiceSheet(title = s.settingsSubtitleStyle, onDismiss = onClose, onClose = onClose) {
        // Fixed above the choices, so it stays in view while they scroll. On a television it is
        // held to a third of the panel's height, as a 16:9 frame the panel's width would leave
        // the choices no room on a 540 dp screen.
        SubtitlePreview(
            style,
            Modifier
                .padding(bottom = 8.dp)
                .widthIn(max = if (touch) 560.dp else 300.dp)
                .align(Alignment.CenterHorizontally),
        )
        ChoiceList {
            ChoiceSection(s.settingsSubtitleSize)
            SubtitleSize.entries.forEach { size ->
                val selected = size == style.size
                ChoiceLine(
                    title = size.label,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(first) else Modifier,
                ) { onChange(style.copy(size = size)) }
            }
            ChoiceSection(s.settingsSubtitleBox)
            ChoiceLine(title = s.commonOff, detail = s.settingsSubtitleBoxOff, selected = !style.box) {
                onChange(style.copy(box = false))
            }
            ChoiceLine(title = s.commonOn, detail = s.settingsSubtitleBoxOn, selected = style.box) {
                onChange(style.copy(box = true))
            }
            ChoiceSection(s.settingsSubtitlePosition)
            SubtitlePosition.entries.forEach { position ->
                ChoiceLine(title = position.label, selected = position == style.position) {
                    onChange(style.copy(position = position))
                }
            }
        }
        FocusOnOpen(first)
    }
}

/**
 * Settings, Video size limits: the range in words, the two-ended slider (a remote moves one end at
 * a time, OK switching ends), and Reset to the defaults beside Close.
 */
@Composable
private fun SizeLimitsDialog(
    minSize: Long,
    maxSize: Long,
    editingUpper: Boolean,
    rangeRow: FocusRequester,
    onSwitchEnd: () -> Unit,
    onStep: (upper: Boolean, direction: Int) -> Unit,
    onSetRange: (min: Long, max: Long) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val range = s.formatter.sizeRange(minSize, maxSize)
    ChoiceSheet(
        title = s.settingsSizeLimits,
        note = if (minSize > SizeFilter.FLOOR) s.settingsSizeLimitsNote(range) else range,
        onDismiss = onClose,
        onClose = onClose,
    ) {
        RangeRow(
            minValue = minSize,
            maxValue = maxSize,
            modifier = Modifier.focusRequester(rangeRow),
            editingUpper = editingUpper,
            onSwitchEnd = onSwitchEnd,
            onStep = onStep,
            onSetRange = onSetRange,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            ResetChip(enabled = !SizeFilter.isDefault(minSize, maxSize)) {
                // Hand focus to the range before resetting: the chip greys itself out the moment
                // the defaults are back, and the range is what the press changed.
                runCatching { rangeRow.requestFocus() }
                onSetRange(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)
            }
        }
        FocusOnOpen(rangeRow)
    }
}
