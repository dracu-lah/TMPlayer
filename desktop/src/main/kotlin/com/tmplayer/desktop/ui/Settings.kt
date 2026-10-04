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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SizeFilter
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.release
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The settings that mean something on a desktop: theme, playback, the chat to open on launch,
 * the size limits, storage, history and signing out. Voice search is not here at all (no portable
 * speech API, B2.5), nor are the phone's touch and orientation settings.
 */
@Composable
fun SettingsPage(state: ShellState, version: String = "") {
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
    var used by remember { mutableLongStateOf(-1L) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { used = runCatching { Td.storageUsedBytes() }.getOrDefault(-1L) }

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

                Group("Storage")
                Setting(
                    "Telegram's files on this computer",
                    if (used < 0) "Working it out…" else StreamStats.formatBytes(used),
                ) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { Td.clearPicturesAndPreviews() }
                            used = runCatching { Td.storageUsedBytes() }.getOrDefault(-1L)
                            toast("Pictures and previews cleared")
                        }
                    }) { Text("Clear pictures") }
                }
                state.extras.watchCache?.let { cache ->
                    Setting("Cached videos", "What streaming left on the disk. Downloads stay") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val freed = runCatching { cache.clearAll() }.getOrDefault(0L)
                                used = runCatching { Td.storageUsedBytes() }.getOrDefault(-1L)
                                toast(if (freed > 0) "${StreamStats.formatBytes(freed)} freed" else "No cached videos to clear")
                            }
                        }) { Text("Clear cache") }
                    }
                }

                Group("History")
                Setting("Continue watching", "Forget where every video was stopped") {
                    OutlinedButton(onClick = {
                        scope.launch {
                            settings.clearWatchHistory()
                            toast("Continue watching cleared")
                        }
                    }) { Text("Clear") }
                }
                Setting("Favourites", "Take the star off every chat") {
                    OutlinedButton(onClick = {
                        scope.launch {
                            settings.clearFavorites()
                            toast("Favourites cleared")
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

                Group("Account")
                Setting("Sign out of Telegram", "Downloads, favourites and history go with it") {
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = Tone.danger) }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Telegram?") },
            text = {
                Text(
                    "You'll be signed out and taken back to the sign in screen. The downloaded videos, " +
                        "your favourites and everything you were part way through go with it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        // Everything this app knows is about the account that is leaving.
                        runCatching { Td.clearMediaCache() }
                        runCatching { settings.clearEverything() }
                        state.go(Destination.Chats)
                        Td.logOut()
                    }
                }) { Text("Sign out", color = Tone.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.accent)
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Tone.outline)
    }
}

@Composable
private fun Setting(title: String, detail: String, titleColor: Color = Color.Unspecified, control: @Composable () -> Unit) {
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
private fun Stepper(onLess: () -> Unit, onMore: () -> Unit) {
    Row {
        IconButton(onClick = onLess) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
        IconButton(onClick = onMore) { Icon(Icons.Filled.Add, contentDescription = "More") }
    }
}
