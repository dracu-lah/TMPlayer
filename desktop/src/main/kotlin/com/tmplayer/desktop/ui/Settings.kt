package com.tmplayer.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SizeFilter
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.release
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.ui.about.About
import com.tmplayer.player.TouchPrefs
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The settings that mean something on a desktop: theme, playback, the chat to open on launch,
 * the size limits, storage, history and signing out. Voice search is not here at all (no portable
 * speech API, B2.5), nor are the phone's touch and orientation settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(state: ShellState, version: String = "") {
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutPage(version, onBack = { showAbout = false })
        return
    }
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val autoplay by settings.autoplayNext.collectAsState(initial = true)
    val downloadFirst by settings.downloadBeforePlaying.collectAsState(initial = false)
    val openLast by settings.openLastChat.collectAsState(initial = false)
    val minSize by settings.minSizeBytes.collectAsState(initial = SizeFilter.FLOOR)
    val maxSize by settings.maxSizeBytes.collectAsState(initial = SizeFilter.CEILING)
    val desktop by state.extras.prefs.state.collectAsState()
    val notifyUpdates by settings.updateNotify.collectAsState(initial = true)
    val updateState by Updates.state.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val lastChatId by settings.lastChatId.collectAsState(initial = 0L)
    val history by settings.continueWatching.collectAsState(initial = emptyList())
    val favourites by settings.favorites.collectAsState(initial = emptySet())
    val watchedList by state.watched.history.collectAsState(initial = emptyList())
    var confirmClearWatched by remember { mutableStateOf(false) }
    // The "Change" under a chat's grid lands here, on the size limits.
    val sizeLimits = remember { BringIntoViewRequester() }
    LaunchedEffect(state.showSizeLimits) {
        if (!state.showSizeLimits) return@LaunchedEffect
        // One frame, so the rows exist before they are asked to come into view.
        withFrameNanos { }
        runCatching { sizeLimits.bringIntoView() }
        state.showSizeLimits = false
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            PageHeader("Settings", if (version.isBlank()) null else "TMPlayer $version")
            Column(
                Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Group("Appearance")
                Setting("Theme", theme.description.replace("phone", "computer")) {
                    SingleChoiceSegmentedButtonRow {
                        ThemeChoice.entries.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = theme == choice,
                                onClick = { scope.launch { settings.setThemeChoice(choice) } },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                            ) { Text(choice.label) }
                        }
                    }
                }

                Group("Playback")
                Toggle("Play the next episode", "When a video ends, start the one after it", autoplay) {
                    scope.launch { settings.setAutoplayNext(it) }
                }
                Toggle("Download the whole video first", "Wait for the file instead of streaming it", downloadFirst) {
                    scope.launch { settings.setDownloadBeforePlaying(it) }
                }
                Setting(
                    "Hide the controls after",
                    "${TouchPrefs.timeoutLabel(touchPrefs.controlsTimeoutMs)} without the mouse moving. A paused video keeps them up",
                ) {
                    val choices = TouchPrefs.TIMEOUT_CHOICES_MS
                    Stepper(
                        onLess = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, -1)) } } },
                        onMore = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, 1)) } } },
                    )
                }

                Toggle(
                    "Mouse wheel seeks",
                    if (desktop.wheelSeeks) "The wheel skips 10 s; Shift and the wheel change the volume" else "The wheel changes the volume; Shift and the wheel skip 10 s",
                    desktop.wheelSeeks,
                ) { on -> state.extras.prefs.update { it.copy(wheelSeeks = on) } }
                Toggle("Downmix to stereo", "Fold surround sound into two speakers or headphones", desktop.downmix) { on ->
                    state.extras.prefs.update { it.copy(downmix = on) }
                }
                Toggle(
                    "Force software decoding",
                    if (DesktopSettings.defaultSoftwareDecoding()) {
                        "On by default on Linux, where the bundled video drivers rarely match the system's. Turn off to try the graphics card"
                    } else {
                        "Decode on the processor instead of the graphics card, if videos show green or broken frames"
                    },
                    desktop.softwareDecoding,
                ) { on -> state.extras.prefs.update { it.copy(softwareDecoding = on) } }

                Group("Library")
                Toggle("Open the last chat on launch", "Start where you left off rather than on the chat list", openLast) {
                    scope.launch { settings.setOpenLastChat(it) }
                }
                if (openLast && lastChatId != 0L) {
                    Setting("Forget the last chat", "Start at the chat list again until you open another chat") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                settings.forgetLastChat()
                                // The row goes away on success, so the toast is the only sign.
                                toast("TMPlayer will start at the chat list")
                            }
                        }) { Text("Forget") }
                    }
                }
                Box(Modifier.bringIntoViewRequester(sizeLimits)) {
                Setting("Smallest video shown", SizeFilter.label(minSize)) {
                    Stepper(
                        onLess = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, -1), maxSize)) } },
                        onMore = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, 1), maxSize)) } },
                    )
                }
                Setting("Largest video shown", SizeFilter.label(maxSize)) {
                    Stepper(
                        onLess = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, -1), minSize)) } },
                        onMore = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, 1), minSize)) } },
                    )
                }
                }
                if (minSize != SizeFilter.FLOOR || maxSize != SizeFilter.CEILING) {
                    Setting("Size limits", "Show every video again, whatever its size") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                settings.setMinSizeBytes(SizeFilter.FLOOR)
                                settings.setMaxSizeBytes(SizeFilter.CEILING)
                            }
                        }) { Text("Reset") }
                    }
                }

                StorageGroup(state)

                Group("History")
                Setting("Continue watching", "Forget where every video was stopped") {
                    OutlinedButton(onClick = {
                        if (history.isEmpty()) {
                            toast("Nothing in Continue watching")
                            return@OutlinedButton
                        }
                        confirm = Confirm(
                            title = "Clear Continue watching?",
                            message = "Every video you have part watched is forgotten, and the page empties.",
                            detail = "Nothing is deleted from Telegram; each video stays in the chat it came from.",
                        ) {
                            scope.launch {
                                val count = history.size
                                settings.clearWatchHistory()
                                toast(if (count == 1) "Continue watching cleared" else "$count videos forgotten")
                            }
                        }
                    }) { Text("Clear") }
                }
                Setting(
                    "Clear watched list",
                    when (watchedList.size) {
                        0 -> "Take the watched tick off every video"
                        1 -> "Take the tick off the one video you have watched"
                        else -> "Take the tick off all ${watchedList.size} videos you have watched"
                    },
                ) {
                    OutlinedButton(onClick = {
                        if (watchedList.isEmpty()) toast("Nothing in Previously watched") else confirmClearWatched = true
                    }) { Text("Clear") }
                }
                Setting("Favourites", "Take the star off every chat") {
                    OutlinedButton(onClick = {
                        if (favourites.isEmpty()) {
                            toast("No chats are starred")
                            return@OutlinedButton
                        }
                        confirm = Confirm(
                            title = "Clear favourites?",
                            message = if (favourites.size == 1) {
                                "The one starred chat loses its star and Favourites empties."
                            } else {
                                "All ${favourites.size} chats lose their star and Favourites empties."
                            },
                            detail = "The chats themselves stay where they are, in the chat list.",
                        ) {
                            scope.launch {
                                val count = favourites.size
                                settings.clearFavorites()
                                toast(if (count == 1) "Favourite cleared" else "$count favourites cleared")
                            }
                        }
                    }) { Text("Clear") }
                }

                state.extras.updates?.let { updates ->
                    Group("Updates")
                    Toggle(
                        "Tell me when a new version is out",
                        "Looks every six hours. Nothing is downloaded or installed without you",
                        notifyUpdates,
                    ) { on -> scope.launch { settings.setUpdateNotify(on) } }
                    val offered = updateState.release
                    if (offered != null) {
                        Setting(UpdateWords.settingsRow(offered), UpdateWords.youHave(version), titleColor = Tone.caution) {
                            OutlinedButton(onClick = { state.updatePopup = true }) { Text("Open", color = Tone.caution) }
                        }
                    }
                    Setting("Check for updates", "TMPlayer $version") {
                        var checking by remember { mutableStateOf(false) }
                        OutlinedButton(enabled = !checking, onClick = {
                            checking = true
                            scope.launch {
                                updates.checkNow()
                                checking = false
                                // Anything to offer opens the popup, a skipped version included;
                                // the rest is a sentence.
                                when (val after = Updates.state.value) {
                                    is UpdateState.Available -> state.updatePopup = true
                                    is UpdateState.Failed -> {
                                        toast(after.message)
                                        Updates.dismiss()
                                    }
                                    else -> toast("TMPlayer $version is the newest version")
                                }
                            }
                        }) { Text(if (checking) "Checking" else "Check") }
                    }
                }

                Group("Help")
                Setting("Privacy", "What stays on this computer and which services TMPlayer contacts") {
                    OutlinedButton(onClick = { OpenExternal.browse(About.PRIVACY) }) { Text("Open") }
                }
                Setting("Lawful use", "Use TMPlayer only with media you may access") {
                    OutlinedButton(onClick = { OpenExternal.browse(About.LEGAL) }) { Text("Open") }
                }
                Setting("About TMPlayer", "Version $version, licence, notices, contact and support") {
                    OutlinedButton(onClick = { showAbout = true }) { Text("Open") }
                }

                Group("Account")
                Setting("Sign out of Telegram", "Favourites, history and the watched list go with it. Downloads stay unless you say otherwise") {
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = Tone.danger) }
                }
                Text(
                    "TMPlayer talks directly to Telegram for your chats and videos, and to " +
                        "tmplayer.org, or GitHub when the site cannot be reached, to see whether a " +
                        "newer version is out. That check reads one static file, the same for " +
                        "everyone, and sends nothing about you. TMPlayer has no account or backend " +
                        "of its own, and no analytics or advertising SDK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    if (confirmSignOut) SignOutDialog(state, onDismiss = { confirmSignOut = false })
    if (confirmClearWatched) {
        ClearWatchedDialog(state, watchedList.size, scope, onDismiss = { confirmClearWatched = false })
    }

    confirm?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            detail = asked.detail,
            confirmLabel = "Clear",
            onConfirm = {
                confirm = null
                asked.action()
            },
            onDismiss = { confirm = null },
        )
    }
}

/** A clear waiting on its yes: what the prompt says, and what happens on Clear. */
private class Confirm(val title: String, val message: String, val detail: String?, val action: () -> Unit)

@Composable
internal fun Group(title: String) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.accent)
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Tone.outline)
    }
}

@Composable
internal fun Setting(title: String, detail: String, titleColor: Color = Color.Unspecified, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        }
        control()
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Setting(title, detail) { Switch(checked = checked, onCheckedChange = onChange) }
}

@Composable
internal fun Stepper(onLess: () -> Unit, onMore: () -> Unit) {
    Row {
        IconButton(onClick = onLess) { Icon(TmIcons.Remove, contentDescription = "Less") }
        IconButton(onClick = onMore) { Icon(Icons.Filled.Add, contentDescription = "More") }
    }
}
