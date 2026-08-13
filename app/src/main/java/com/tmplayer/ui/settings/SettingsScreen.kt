package com.tmplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton as TouchIconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CrashReports
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.WatchCache
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateState
import com.tmplayer.data.Updates
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Prompt {
    /** The one video watching left behind. */
    data object ClearCache : Prompt

    /** Everything else TMPlayer is holding: previews, thumbnails and the database. */
    data object ClearOther : Prompt
    data object ClearHistory : Prompt
    data object ClearFavorites : Prompt
    data object SignOut : Prompt
}

/**
 * Below this, what a clear would return is scraps and the wording should not promise otherwise.
 */
private const val CLEARING_WORTH_ASKING = CacheShelf.WORTH_ASKING_BYTES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    chats: List<ChatSummary>,
    onLoggedOut: () -> Unit,
    /** Leaving Settings. On a phone this is the app bar's arrow as well as the hardware key. */
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    // Matches the stored default, so the switch does not show as on for the frame before the
    // first value arrives and then visibly flick off.
    val openLastChat by settings.openLastChat.collectAsStateWithLifecycle(initialValue = false)
    // The single video watching leaves behind. Null until the first read, which is the same thing
    // it says when there is none: a row that reads "Nothing cached" for a frame is honest either
    // way, and the size beside it arrives with the rest of the storage figures.
    val cached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadFirst by settings.downloadBeforePlaying.collectAsStateWithLifecycle(initialValue = false)
    val autoplayNext by settings.autoplayNext.collectAsStateWithLifecycle(initialValue = true)
    val wifiOnly by settings.wifiOnlyDownloads.collectAsStateWithLifecycle(initialValue = false)
    val history by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
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
    var showUpdate by remember { mutableStateOf(false) }
    // What TMPlayer is holding, split into downloads, cache and everything else. Worked out by
    // [StorageSplit], which is also what the Downloads screen reads: the two panels were doing
    // their own arithmetic and quoting different numbers for the same disk.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    // Which end of the range the D-pad is currently moving.
    var editingUpper by remember { mutableStateOf(false) }
    // The range row is where focus lands, and where it is sent back to after a reset. It is the
    // first control on the screen and it changes nothing on its own, unlike the delete row further
    // down, which is both destructive and too far down the list to be on screen at all.
    val rangeRow = remember { FocusRequester() }

    suspend fun refresh() {
        disk = DiskSpace.read(context)
        // A TDLib round trip per record and a walk of the files directory, so it is kept off the
        // thread drawing the list: this screen scrolls while it is counting.
        split = withContext(Dispatchers.Default) {
            runCatching { StorageSplit.measure(context) }.getOrDefault(StorageSplit.EMPTY)
        }
    }

    // The case that sends somebody to Settings in the first place: a device holding gigabytes with
    // no video among them, where deleting the cached film returns nothing and the space is all in
    // previews, thumbnails and the database. Only then is there a second row worth offering.
    val onlyPicturesLeft = split.otherBytes >= CLEARING_WORTH_ASKING

    val touch = isTouch()
    // These rows describe the machine they are running on, and half of them are about it by name.
    val device = if (touch) "phone" else "TV"

    // The size beside the cached video has to follow the video: watching something else replaces
    // the record, and a figure left over from the last film is worse than no figure at all.
    LaunchedEffect(cached.map { it.fileId }) { refresh() }

    LaunchedEffect(Unit) {
        refresh()
        // Nothing on a phone is driven by focus, and stealing it here would scroll the list to a
        // control the viewer never asked for.
        if (!touch) runCatching { rangeRow.requestFocus() }
    }

    // Named, when the chat list has loaded. On a cold start into Settings it may not have, and
    // the setting still has to describe itself, so the wording below covers both.
    val lastChatTitle = remember(chats, lastChatId) {
        chats.firstOrNull { it.id == lastChatId }?.title
    }

    // Overscan is a television's problem: a phone's are the status bar, the gesture handle and,
    // in landscape, a notch down one side. The list itself still runs the full height, so content
    // scrolls under the bars rather than stopping short of them.
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    val list: @Composable () -> Unit = {
    LazyColumn(
        Modifier
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
        // Full-bleed rows sit closer together than cards did: the gap between two rows of a
        // preference list is a divider's worth of nothing, not a card margin.
        verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 10.dp),
    ) {
        // The phone's app bar already says "Settings" and offers the way out, so repeating both
        // one line below reads as a mistake. "Changes save as you make them." goes with them: a
        // preference screen that saves as you go is what every Android user already assumes, and
        // the sentence was the first thing on the screen either way.
        if (!touch) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Tone.text,
                    )
                    Text(
                        "Changes save as you make them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
            }
        }

        // Whatever is currently running, at the top rather than below the last row. "Deleting…"
        // and "Signing out…" were written under a list the viewer would have to scroll past the
        // end of to read, which is the one place they cannot be looking when they press the row.
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

        // ---- appearance ----------------------------------------------------------------------

        // First, on both devices. The television used to be left out of this section entirely, on
        // the reasoning that a panel in a dark room is dark: true of the evening, and no answer at
        // all for a screen in a bright kitchen, which is where a good many of these sticks live.
        item { SectionTitle("Appearance") }
        item {
            if (touch) {
                ThemePicker(
                    current = themeChoice,
                    onPick = { scope.launch { settings.setThemeChoice(it) } },
                )
            } else {
                // Segments are a thumb control: they cannot be reached with a D-pad, which is why
                // the picker was never offered here. The stepper is the same one the size limits
                // and the video count use, so Left and Right already mean "change this".
                StepperRow(
                    title = "Theme",
                    subtitle = themeChoice.tvDescription,
                    value = themeChoice.label,
                    icon = TmIcons.CircleOutline,
                    canDecrease = themeChoice.ordinal > 0,
                    canIncrease = themeChoice.ordinal < ThemeChoice.entries.lastIndex,
                    onStep = { direction ->
                        val next = ThemeChoice.entries.getOrNull(themeChoice.ordinal + direction)
                        if (next != null) scope.launch { settings.setThemeChoice(next) }
                    },
                )
            }
        }

        if (touch) {
            // Android 11 and earlier has no wallpaper palette to read, so the row would be a
            // switch that changes nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    ToggleRow(
                        title = "Use the wallpaper's colours",
                        subtitle = if (dynamicColour) {
                            "TMPlayer takes its colours from your phone's wallpaper"
                        } else {
                            "Off: TMPlayer uses its own colours"
                        },
                        icon = TmIcons.CircleOutline,
                        checked = dynamicColour,
                        onToggle = { scope.launch { settings.setDynamicColour(!dynamicColour) } },
                    )
                }
            }
        }

        // ---- what shows up --------------------------------------------------------------

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Lined up with the headline of a ListItem below, which carries 16dp of its
                    // own, so the heading does not start to the left of everything it heads.
                    .padding(
                        start = if (touch) 16.dp else 0.dp,
                        end = if (touch) 8.dp else 0.dp,
                        top = 16.dp,
                        bottom = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Video size limits",
                    style = sectionStyle(),
                    color = if (touch) Tone.accent else Tone.text,
                    modifier = Modifier.weight(1f),
                )
                ResetChip(
                    enabled = minSize != SizeFilter.DEFAULT_MIN || maxSize != SizeFilter.DEFAULT_MAX,
                ) {
                    // Hand focus to the range row before resetting. The chip greys itself out the
                    // moment the defaults are back, and focus left sitting on a spent control has
                    // nowhere obvious to go; the row below is what the press just changed anyway.
                    runCatching { rangeRow.requestFocus() }
                    scope.launch {
                        // Widen first, so the clamp that stops the two ends crossing cannot
                        // block the new floor on its way past the old ceiling.
                        settings.setMaxSizeBytes(SizeFilter.DEFAULT_MAX)
                        settings.setMinSizeBytes(SizeFilter.DEFAULT_MIN)
                    }
                }
            }
        }
        item {
            Text(
                // Phrased around whichever ends are actually set.
                SizeFilter.describe(minSize, maxSize) +
                    " This keeps short clips out of the list.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.padding(
                    bottom = 4.dp,
                    start = if (touch) 16.dp else 4.dp,
                    end = if (touch) 16.dp else 0.dp,
                ),
            )
        }
        item {
            RangeRow(
                minValue = minSize,
                maxValue = maxSize,
                modifier = Modifier.focusRequester(rangeRow),
                editingUpper = editingUpper,
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
                        // Widened first for the same reason the reset chip does it: the clamp
                        // that stops the ends crossing would otherwise refuse a new floor on
                        // its way past the old ceiling.
                        settings.setMaxSizeBytes(high)
                        settings.setMinSizeBytes(low)
                    }
                },
            )
        }

        // ---- lists ---------------------------------------------------------------------------

        // Rows or tiles used to be set here. It now sits beside each list it rearranges, where
        // the viewer can see what they are choosing between.
        // The heading only appears when it has something under it. Both rows are conditional, so
        // a viewer with no favourites and nothing on the go was shown "Lists" over empty space.
        if (favorites.isNotEmpty() || history.isNotEmpty()) {
            item { SectionTitle("Lists") }
        }
        if (favorites.isNotEmpty()) {
            item {
                ActionRow(
                    title = "Clear favourites",
                    subtitle = if (favorites.size == 1) {
                        "Unstars the one chat you have starred"
                    } else {
                        "Unstars all ${favorites.size} starred chats"
                    },
                    icon = Icons.Filled.Close,
                    onClick = { prompt = Prompt.ClearFavorites },
                )
            }
        }
        if (history.isNotEmpty()) {
            item {
                ActionRow(
                    title = "Clear Continue watching",
                    subtitle = if (history.size == 1) {
                        "Forgets the one video you have on the go"
                    } else {
                        "Forgets all ${history.size} videos you have on the go"
                    },
                    icon = Icons.Filled.Close,
                    onClick = { prompt = Prompt.ClearHistory },
                )
            }
        }

        // ---- playback -----------------------------------------------------------------------

        item { SectionTitle("Playback") }
        item {
            ToggleRow(
                title = "Play the next episode automatically",
                subtitle = if (autoplayNext) {
                    "Starts after a short countdown you can stop"
                } else {
                    "Off: a finished video stays on the last frame"
                },
                icon = Icons.Filled.PlayArrow,
                checked = autoplayNext,
                onToggle = { scope.launch { settings.setAutoplayNext(!autoplayNext) } },
            )
        }
        item {
            ToggleRow(
                title = "Download the whole video first",
                subtitle = if (downloadFirst) {
                    "Waits for all of it, then plays"
                } else {
                    "Off: it downloads while you watch"
                },
                icon = TmIcons.Clock,
                checked = downloadFirst,
                onToggle = {
                    scope.launch { settings.setDownloadBeforePlaying(!downloadFirst) }
                },
            )
        }
        // A phone's row only. A television is on the wall and a stick is behind it, both plugged
        // into a network that is Wi-Fi or a cable and never a data allowance somebody pays for by
        // the gigabyte, so on a TV this asks the viewer to rule out something that cannot happen.
        if (touch) {
            item {
                ToggleRow(
                    title = "Only download over Wi-Fi",
                    subtitle = if (wifiOnly) {
                        "Videos you haven't downloaded won't open on mobile data"
                    } else {
                        "Off: a large video warns you once before it starts on mobile data"
                    },
                    icon = TmIcons.Wifi,
                    checked = wifiOnly,
                    onToggle = { scope.launch { settings.setWifiOnlyDownloads(!wifiOnly) } },
                )
            }
        }

        // ---- storage ------------------------------------------------------------------------

        item { SectionTitle("Storage") }
        item {
            StorageCard(
                split = split,
                freeBytes = disk.freeBytes,
                totalBytes = disk.totalBytes,
            )
        }
        // The one video watching leaves behind, named and measured, with the way to be rid of it.
        // A row rather than a setting: there is nothing to choose here, since the cache is one
        // film on every device and the next press of Play replaces it. What the viewer might
        // reasonably want is to see what it is costing them and get the space back now.
        item {
            val held = cached.firstOrNull()
            ActionRow(
                // "Cached video" named the thing; this names the action, which is what the row
                // actually does and what somebody hunting for space is looking for.
                title = "Clear cache",
                subtitle = when {
                    split.cachedBytes <= 0 -> "Nothing cached. Playing a video keeps a copy here."
                    // More than one means episodes an older version of the app left behind. Saying
                    // "1 video" and quoting two gigabytes is the confusing part, so it counts.
                    split.cachedCount > 1 ->
                        "${split.cachedCount} videos · ${StreamStats.formatBytes(split.cachedBytes)}"
                    held != null -> "\"${held.title}\" · ${StreamStats.formatBytes(split.cachedBytes)}"
                    else -> StreamStats.formatBytes(split.cachedBytes)
                },
                icon = TmIcons.Download,
                onClick = {
                    // Nothing to delete is not a dialog. The row stays where it is, focusable and
                    // in the same place every time, and says so instead.
                    if (split.cachedBytes <= 0) {
                        toast("There is nothing cached right now")
                    } else {
                        prompt = Prompt.ClearCache
                    }
                },
            )
        }
        // The escape hatch for a device that is full of everything except a video: thumbnails, the
        // database, the previews the browse screens keep. Only offered when there is something
        // substantial to get back, because a row that reads "Frees up 0 B" on a full stick is the
        // least helpful thing the screen could say.
        if (onlyPicturesLeft) {
            item {
                ActionRow(
                    // Named for what it clears, beside the row above it that clears videos. "Free
                    // up space" said neither, and two rows that both sound like the general answer
                    // to a full disk are two rows nobody can choose between.
                    title = "Clear pictures and previews",
                    subtitle = "Frees ${StreamStats.formatBytes(split.otherBytes)} of " +
                        "thumbnails and other data TMPlayer is holding. They come back as you " +
                        "browse.",
                    icon = Icons.Filled.Delete,
                    onClick = { prompt = Prompt.ClearOther },
                )
            }
        }

        // ---- startup ------------------------------------------------------------------------

        item { SectionTitle("On launch") }
        item {
            ToggleRow(
                title = "Carry on from the last chat",
                subtitle = when {
                    lastChatTitle != null -> "Opens $lastChatTitle"
                    lastChatId != 0L -> "Opens the chat you watched last"
                    else -> "Skips the chat list once you have watched something"
                },
                icon = TmIcons.Clock,
                checked = openLastChat,
                onToggle = { scope.launch { settings.setOpenLastChat(!openLastChat) } },
            )
        }
        if (openLastChat && lastChatId != 0L) {
            item {
                ActionRow(
                    title = "Forget it",
                    subtitle = "Start at the chat list again until you open another chat",
                    icon = Icons.Filled.Close,
                    // This row deletes itself on success, so without a word it reads as the app
                    // having lost the press rather than having done what was asked.
                    onClick = {
                        scope.launch {
                            settings.forgetLastChat()
                            toast("TMPlayer will start at the chat list")
                        }
                    },
                )
            }
        }

        // ---- version -------------------------------------------------------------------------

        item { SectionTitle("Version") }
        (updateState as? UpdateState.Available)?.let { available ->
            item {
                ActionRow(
                    title = "Update to ${available.release.version}",
                    subtitle = "Downloads " +
                        "${StreamStats.formatBytes(available.release.sizeBytes)} from GitHub, " +
                        "then Android asks you to confirm",
                    icon = Icons.Filled.Refresh,
                    tint = Tone.caution,
                    onClick = { showUpdate = true },
                )
            }
        }
        item {
            ActionRow(
                title = "Check for updates",
                subtitle = "This is TMPlayer ${Updates.installedVersion}, from " +
                    Updates.RELEASES_PAGE,
                icon = Icons.Filled.Refresh,
                onClick = {
                    showUpdate = true
                    scope.launch { Updates.check() }
                },
            )
        }

        // ---- help ----------------------------------------------------------------------------

        item { SectionTitle("Help") }
        item {
            ActionRow(
                title = "Show the walkthrough again",
                subtitle = "How TMPlayer works, in a few screens",
                icon = Icons.Filled.Info,
                onClick = { scope.launch { settings.replayOverview() } },
            )
        }
        item {
            ActionRow(
                title = "Privacy",
                subtitle = "What stays on this $device and which services TMPlayer contacts",
                icon = Icons.Filled.Info,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://tmplayer.org/privacy")),
                        )
                    }
                },
            )
        }
        // The one setting that sends anything anywhere except Telegram and GitHub, and it is off
        // until it is asked for. A build with no DSN compiled in has nowhere to send a report, so
        // rather than offer a switch that does nothing, it is not drawn at all.
        if (CrashReports.available) {
            item {
                ToggleRow(
                    title = "Send crash reports",
                    subtitle = if (crashReports) {
                        "If TMPlayer crashes, the stack trace is sent to the developer. " +
                            "No chat names, no filenames, no history"
                    } else {
                        "Off: nothing is sent. Turn this on to help fix the crash you just had"
                    },
                    icon = Icons.Filled.Info,
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
                title = "Lawful use",
                subtitle = "Use TMPlayer only with media you may access",
                icon = Icons.Filled.Info,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://tmplayer.org/legal")),
                        )
                    }
                },
            )
        }

        // ---- account ------------------------------------------------------------------------

        item { SectionTitle("Account") }
        item {
            ActionRow(
                title = "Sign out of Telegram",
                subtitle = "This $device will stop appearing in your Telegram devices",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                onClick = { prompt = Prompt.SignOut },
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
                    "TMPlayer talks directly to Telegram for your chats and videos, and to GitHub " +
                        "to see whether a newer version is out. It has no developer-run server, " +
                        "analytics or advertising SDK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                )
            }
        }
    }
    }

    if (touch) {
        // A real app bar, because before this there was no way off the phone's Settings screen at
        // all except the hardware Back: no arrow, no title bar, nothing. The heading was a line of
        // text scrolling away with the rest of the list.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { M3Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            M3Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to chats",
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

    when (prompt) {
        Prompt.ClearCache -> TvConfirm(
            title = if (split.cachedCount > 1) "Clear ${split.cachedCount} cached videos?" else
                "Clear the cached video?",
            message = "This frees up ${StreamStats.formatBytes(split.cachedBytes)}. Opening them again " +
                "downloads them again.",
            // Says what it will not touch, because this row sits where a button that deleted
            // everything used to sit and the two must not be confused.
            detail = "Videos you downloaded on purpose stay where they are.",
            confirmLabel = "Clear",
            onConfirm = {
                prompt = null
                val doomed = cached
                scope.launch {
                    busy = "Clearing…"
                    val freed = split.cachedBytes
                    val keptIds = runCatching { settings.downloadHistory.first() }
                        .getOrDefault(emptyList())
                        .map { it.fileId }
                        .toSet()
                    for (record in doomed) {
                        if (record.fileId in keptIds) continue
                        runCatching { Td.deleteFile(record.fileId) }
                        // Only forgotten once the file has actually gone, or the record stops
                        // pointing at bytes that are still on the disk.
                        val left = runCatching { Td.localDownloadedBytes(record.fileId) }
                            .getOrDefault(0L)
                        if (left <= 0) settings.forgetCachedVideo(record.chatId, record.messageId)
                    }
                    // And the episodes left behind by the versions of this app that kept one
                    // record for the whole cache. They have no record to delete, only bytes.
                    val accounted = (doomed.map { it.fileId } + keptIds)
                        .distinct()
                        .mapNotNull { runCatching { Td.localPathAnyway(it) }.getOrNull() }
                        .toSet()
                    for (stray in WatchCache.strays(context, accounted)) WatchCache.forget(stray)
                    refresh()
                    busy = null
                    toast("Freed ${StreamStats.formatBytes(freed)}")
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearOther -> TvConfirm(
            title = "Free up space?",
            message = "This clears ${StreamStats.formatBytes(split.otherBytes)} of previews, " +
                "thumbnails and other data TMPlayer is holding. They come back as you browse.",
            detail = "Your Telegram account, chats and favourites are untouched.",
            confirmLabel = "Free it up",
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = "Deleting…"
                    val freed = split.otherBytes
                    runCatching { Td.clearEverythingCached() }
                    // The indexes go with the files. Left behind, their rows point at videos that
                    // are no longer on disk.
                    runCatching { settings.forgetAllDownloads() }
                    runCatching { settings.forgetCachedVideo() }
                    refresh()
                    busy = null
                    toast("Freed ${StreamStats.formatBytes(freed)}")
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearHistory -> TvConfirm(
            title = "Clear Continue watching?",
            message = "Every video you have part-watched is forgotten, and the tab empties.",
            detail = "Nothing is deleted from Telegram; each video stays in the chat it came from.",
            confirmLabel = "Clear",
            onConfirm = {
                prompt = null
                scope.launch {
                    val count = history.size
                    settings.clearWatchHistory()
                    // The rows being cleared are on the browse screen, not this one; the only
                    // thing that changes here is a row disappearing further down the list.
                    toast(if (count == 1) "Continue watching cleared" else "$count videos forgotten")
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.ClearFavorites -> TvConfirm(
            title = "Clear favourites?",
            message = "All ${favorites.size} chats lose their star and the Favourites tab empties.",
            detail = "The chats themselves stay where they are, in Recent and All chats.",
            confirmLabel = "Clear",
            onConfirm = {
                prompt = null
                scope.launch {
                    val count = favorites.size
                    settings.clearFavorites()
                    toast(if (count == 1) "Favourite cleared" else "$count favourites cleared")
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.SignOut -> TvConfirm(
            title = "Sign out of Telegram?",
            message = "You'll be signed out and taken back to the sign-in screen. The downloaded " +
                "video, your favourites and everything you were part-way through go with it.",
            confirmLabel = "Sign out",
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = "Signing out…"
                    // Everything this app knows is about the account that is leaving, so it all
                    // goes: the video on disk and every preference. TDLib clears its own database
                    // as it logs out.
                    runCatching { Td.clearMediaCache() }
                    // Consent to crash reporting goes out with the preferences, and the SDK is
                    // shut down here rather than left running until the next launch. Somebody
                    // handing the television on should not leave a reporter switched on behind
                    // them, and turning it back on is one press.
                    runCatching { CrashReports.stop() }
                    runCatching { settings.clearEverything() }
                    Td.logOut()
                    onLoggedOut()
                }
            },
            onDismiss = { prompt = null },
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
 * None of that exists for a finger. A phone sends no key events at all, so the whole control was
 * inert there: the numbers could be read and never changed. The touch layout drops the idea of a
 * live end entirely and gives each end its own pair of buttons, which needs no explaining and no
 * mode to keep track of.
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
    // 0.8 rather than the 0.6 this was: the second line has to stay legible against the
    // accent, and anything lighter drops under the 4.5:1 a small caption needs.
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
                    // OK swaps which end moves, so one control covers both without the viewer
                    // having to work out which of two identical-looking rows they are on.
                    Key.DirectionCenter, Key.Enter -> { onSwitchEnd(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            End("From", SizeFilter.label(minValue), active = !editingUpper, onSurface, dim, focused)
            Spacer(Modifier.weight(1f))
            End("Up to", SizeFilter.label(maxValue), active = editingUpper, onSurface, dim, focused)
        }
        Spacer(Modifier.height(12.dp))
        RangeTrack(minValue, maxValue, editingUpper, focused)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The ends of the track say what the ends of the range would read as, so the track
            // and the "From" / "Up to" captions above it cannot disagree with each other.
            Text(
                SizeFilter.label(SizeFilter.FLOOR),
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
                    // glyph for those, and a row of tofu boxes would be the only explanation the
                    // viewer gets for a control nothing else on screen describes.
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
                    // Named after the captions overhead, because "upper" and "lower end" is how
                    // the widget was built, not how it reads from the sofa.
                    Text(
                        "change ${if (editingUpper) "Up to" else "From"}   \u00B7   " +
                            "OK switches to ${if (editingUpper) "From" else "Up to"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.onFocusFill,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                SizeFilter.label(SizeFilter.CEILING),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
        }
    }
}

/**
 * The same range, for a screen that is touched rather than pointed at.
 *
 * Both ends are shown at once and each carries its own smaller/larger pair, so there is no live
 * end to keep in mind and nothing that has to be switched before it will move. The track stays,
 * because it is the only thing that says how far apart the two numbers are.
 */
@Composable
private fun TouchRangeRow(
    minValue: Long,
    maxValue: Long,
    onSetRange: (min: Long, max: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held locally while the thumb is down so the track follows the finger at frame rate; the
    // store is written once, when the finger lifts. Keyed on the stored values so a reset from
    // the chip above, or the first value arriving off disk, moves the thumbs.
    var range by remember(minValue, maxValue) {
        mutableStateOf(minValue.toFloat()..maxValue.toFloat())
    }
    val low = SizeFilter.snap(range.start.toLong())
    val high = SizeFilter.snap(range.endInclusive.toLong())

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Tone.surface,
        shape = M3Theme.shapes.large,
    ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        // The two figures above the track rather than in two stepper rows below it: the thumbs
        // are what the viewer is looking at, so the numbers belong where the eye already is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RangeEnd("From", SizeFilter.label(low))
            Spacer(Modifier.weight(1f))
            RangeEnd("Up to", SizeFilter.label(high))
        }
        Spacer(Modifier.height(4.dp))
        // Material's own control, in place of a hand-drawn track and four arrow buttons. Dragging
        // a range is what a phone is for, and the stepper needed eighty presses to cross it. It
        // also brings its own accessibility: TalkBack announces each thumb's value and moves it.
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = {
                onSetRange(
                    SizeFilter.snap(range.start.toLong()),
                    SizeFilter.snap(range.endInclusive.toLong()),
                )
            },
            valueRange = SizeFilter.FLOOR.toFloat()..SizeFilter.CEILING.toFloat(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SizeFilter.label(SizeFilter.FLOOR),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                SizeFilter.label(SizeFilter.CEILING),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
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
    if (isTouch()) {
        // Material's own chip on a phone: it brings the height, the ripple, the disabled colours
        // and the outline that the hand-rolled row below had to spell out one at a time. The
        // television cannot use it, because a chip has no focus appearance to speak of.
        AssistChip(
            onClick = onClick,
            enabled = enabled,
            label = { M3Text("Reset", maxLines = 1) },
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
            // Always clickable, never `enabled = false`: pressing this chip is what greys it out,
            // and a disabled clickable installs no focus target, so the node the viewer was
            // standing on would vanish underneath them and the next press would go nowhere.
            // Greyed out it simply does nothing, and the caller moves focus off it.
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
            "Reset",
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
private fun SectionTitle(text: String) {
    val touch = isTouch()
    Text(
        text,
        style = sectionStyle(),
        // Accent on a phone, which is what every Android preference screen does: with the rows
        // un-carded there is no longer a shape separating one group from the next, so the colour
        // is what does the separating.
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
 * Titles on this screen are two steps down on a phone: at TV size they are nearly as loud as the
 * screen's own heading, which on a narrow column reads as a list of headings with settings hidden
 * between them. `titleSmall` in the primary colour is what a Material preference screen uses, and
 * the colour rather than the size is what marks a heading out there.
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
 * The three options are mutually exclusive and short enough to read at a glance, which is exactly
 * the case a segmented button exists for: a stack of radio rows would take three times the height
 * to say the same thing, and hide two thirds of the answer behind the one that is chosen.
 *
 * A segment carries a check mark as well as its word once it is chosen, so a third of a phone's
 * width is not much room. The segments therefore say only "System", "Light" and "Dark", and the
 * sentence that explains the chosen one sits under the row where it has the whole width to itself
 * rather than being cut off inside a button.
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
    val cacheBytes = split.totalBytes
    val used = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction = if (totalBytes > 0) used.toFloat() / totalBytes else 0f
    val cacheFraction = if (totalBytes > 0) cacheBytes.toFloat() / totalBytes else 0f
    // Until the first measurement lands there is nothing to divide, so the bar keeps its single
    // band and the lines below stay away rather than showing three zeroes.
    val measured = cacheBytes > 0

    val touch = isTouch()
    val bar = if (touch) 10.dp else 14.dp

    // The bands are three roles of one palette on a phone, so they still read as three parts of
    // the same thing when the wallpaper decides what that palette is. A television has no scheme
    // to draw from and keeps the three alphas of the app's own green it was designed with.
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
            "${StreamStats.formatBytes(freeBytes)} free of ${StreamStats.formatBytes(totalBytes)}",
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
                // The bar is three coloured boxes and nothing else, so a screen reader had
                // nothing at all to say about the one graphic on the screen that carries a
                // figure. Merged into a single node, because the parts mean nothing apart.
                .semantics(mergeDescendants = true) {
                    contentDescription = "Storage: " +
                        "${StreamStats.formatBytes(used)} used of " +
                        "${StreamStats.formatBytes(totalBytes)}, " +
                        "${StreamStats.formatBytes(cacheBytes)} of it TMPlayer's, " +
                        if (measured) {
                            "${StreamStats.formatBytes(split.downloadBytes)} downloads, " +
                                "${StreamStats.formatBytes(split.cachedBytes)} cached, " +
                                "${StreamStats.formatBytes(split.otherBytes)} pictures and previews"
                        } else {
                            "still being measured"
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
            "TMPlayer has ${StreamStats.formatBytes(cacheBytes)} saved.",
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
            // screen. They are one measurement drawn twice, and a viewer who reads both should
            // not have to work out whether they disagree.
            if (split.downloadBytes > 0) {
                StorageLegend("Downloads", split.downloadBytes, videoBand)
            }
            if (split.cachedBytes > 0) {
                StorageLegend("Cached from playback", split.cachedBytes, pictureBand)
            }
            if (split.otherBytes > 0) {
                StorageLegend("Pictures and previews", split.otherBytes, otherBand)
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            // The two devices keep different bargains, and the card has to say which one it is
            // looking at: a phone that claimed to replace the last video would be lying about
            // where its space went.
            if (touch) {
                "Playing a video leaves a copy behind, and the next one you play replaces it. " +
                    "Downloads are yours and stay until you delete them, which you can do from " +
                    "the Downloads screen."
            } else {
                "One video is kept at a time; starting another replaces it. Deleting takes the " +
                    "video and leaves the pictures."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
    }
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: ${StreamStats.formatBytes(bytes)}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Spacer(Modifier.weight(1f))
        Text(
            StreamStats.formatBytes(bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.text,
        )
    }
}

/**
 * A row whose value is a number the viewer walks up and down.
 *
 * A phone gets two arrow buttons at the end of the row, which is where Android puts a control that
 * belongs to a setting rather than being the setting. A television has no second target to aim at,
 * so the row itself takes focus and Up and Down on the remote move the number: the arrows are still
 * drawn, greyed at the ends, because they are what says the row can be moved at all.
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
    val touch = isTouch()

    if (touch) {
        ListItem(
            headlineContent = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { M3Text("$value. $subtitle", maxLines = 3) },
            leadingContent = {
                M3Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onStep(-1) }, enabled = canDecrease) {
                        M3Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Fewer")
                    }
                    IconButton(onClick = { onStep(1) }, enabled = canIncrease) {
                        M3Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "More")
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
    // 0.8 rather than the 0.6 this was: the second line has to stay legible against the
    // accent, and anything lighter drops under the 4.5:1 a small caption needs.
    val dim = if (focused) Tone.onFocusFill.copy(alpha = 0.8f) else Tone.muted

    Row(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(Corner.Large))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Large))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Up and Down rather than Left and Right: the rows above and below are the other
                // settings, and a list that moves under Up would strand the viewer on this one.
                // Left and Right lead nowhere on this screen, so they are the pair to spend.
                when (event.key) {
                    Key.DirectionLeft -> { if (canDecrease) onStep(-1); true }
                    Key.DirectionRight -> { if (canIncrease) onStep(1); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp),
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
                if (focused) "$value  ·  Left and Right change it" else "$value. $subtitle",
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
                maxLines = 1,
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

@Composable
private fun ActionRow(
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
            M3Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    FocusRow(
        modifier = Modifier,
        onClick = onToggle,
        headline = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = { M3Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leading = {
            M3Icon(
                icon,
                contentDescription = null,
                tint = Tone.text,
                modifier = Modifier.size(24.dp),
            )
        },
        // A real switch, not a drawing of one. It animates its knob, it can be dragged as well as
        // tapped, and TalkBack announces it as "switch, on" without being told: the drawn pill
        // below reported nothing at all, so the one row on this screen whose state matters most
        // was the one a screen reader could say least about.
        trailing = { M3Switch(checked = checked, onCheckedChange = { onToggle() }) },
    ) { focused ->
        // Carries an icon for the same reason ActionRow does: the two kinds of row are stacked in
        // one column, and without it their titles would start at two different x positions.
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
                maxLines = 1,
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
    // filled in, so on it the live track has to be that fill's own contrast colour; off the row the
    // accent itself is the live one. Drawing it white either way was readable only while the
    // television had a dark accent of its own, and the accent is the phone's now.
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
 * A phone gets Material's own `ListItem`: it owns the two text styles, the leading and trailing
 * insets and the heights that every other Android settings screen is built from, and matching that
 * by hand is how a screen ends up almost right in a dozen small ways. A television cannot use it,
 * because the focused row there has to fill with colour and take its text white with it, which is
 * not something a `ListItem` will do. So the slots are drawn on the phone and [content] on the TV.
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
            // Transparent, so the rows read as a list written on the window rather than a stack of
            // panels: the screen's own background is what shows between them.
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(
                    interactionSource = interactions,
                    indication = LocalIndication.current,
                    // Named as a button, so a screen reader offers "double tap to activate" rather
                    // than reading two lines of text with nothing to do about them.
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
        return
    }

    Row(
        modifier
            .fillMaxWidth()
            // A fixed height is right on a TV, where every subtitle is one line at that width.
            .height(66.dp)
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
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}
