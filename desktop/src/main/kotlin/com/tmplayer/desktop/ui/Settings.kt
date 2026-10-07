package com.tmplayer.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.release
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.player.SubtitlePosition
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.TouchPrefs
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
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
    val s = LocalStrings.current
    var showAbout by remember { mutableStateOf(false) }
    val supportCounters by state.settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    if (showAbout) {
        AboutPage(version, supporter = supportCounters.supporter, onBack = { showAbout = false })
        return
    }
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val autoplay by settings.autoplayNext.collectAsState(initial = true)
    val downloadFirst by settings.downloadBeforePlaying.collectAsState(initial = false)
    val openLast by settings.openLastChat.collectAsState(initial = false)
    val minSize by settings.minSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MAX)
    val desktop by state.extras.prefs.state.collectAsState()
    val notifyUpdates by settings.updateNotify.collectAsState(initial = true)
    val updateState by Updates.state.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var supporting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val subtitleStyle by settings.subtitleStyle.collectAsState(initial = SubtitleStyle())
    val lastChatId by settings.lastChatId.collectAsState(initial = 0L)
    val history by settings.continueWatching.collectAsState(initial = emptyList())
    val favourites by settings.favorites.collectAsState(initial = emptySet())
    val watchedList by state.watched.history.collectAsState(initial = emptyList())
    var confirmClearWatched by remember { mutableStateOf(false) }
    val language by settings.language.collectAsState(initial = "")
    var pickingLanguage by remember { mutableStateOf(false) }
    var stylingSubtitles by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
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
            PageHeader(s.settingsTitle, if (version.isBlank()) null else s.settingsAppVersion(version))
            Column(
                Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Group(s.settingsAppearance)
                Setting(s.settingsTheme, if (theme == ThemeChoice.System) s.themeSystemDesktopDetail else theme.description) {
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

                Setting(s.settingsLanguage, s.languageRowDetail(language)) {
                    OutlinedButton(onClick = { pickingLanguage = true }) { Text(s.commonChange) }
                }

                Group(s.settingsPlayback)
                Toggle(s.settingsAutoplayNext, s.settingsAutoplayDetail, autoplay) {
                    scope.launch { settings.setAutoplayNext(it) }
                }
                Toggle(s.settingsDownloadFirst, s.settingsDownloadFirstDetail, downloadFirst) {
                    scope.launch { settings.setDownloadBeforePlaying(it) }
                }
                Setting(
                    s.settingsHideControls,
                    s.settingsHideControlsDetail(TouchPrefs.timeoutLabel(touchPrefs.controlsTimeoutMs)),
                ) {
                    val choices = TouchPrefs.TIMEOUT_CHOICES_MS
                    Stepper(
                        onLess = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, -1)) } } },
                        onMore = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, 1)) } } },
                    )
                }

                Toggle(
                    s.settingsWheelSeeks,
                    if (desktop.wheelSeeks) s.settingsWheelSeeksOn else s.settingsWheelSeeksOff,
                    desktop.wheelSeeks,
                ) { on -> state.extras.prefs.update { it.copy(wheelSeeks = on) } }
                Toggle(s.playerDownmix, s.settingsDownmixDetail, desktop.downmix) { on ->
                    state.extras.prefs.update { it.copy(downmix = on) }
                }
                Toggle(
                    s.settingsSoftwareDecoding,
                    if (DesktopSettings.defaultSoftwareDecoding()) {
                        s.settingsSoftwareDecodingLinux
                    } else {
                        s.settingsSoftwareDecodingDetail
                    },
                    desktop.softwareDecoding,
                ) { on -> state.extras.prefs.update { it.copy(softwareDecoding = on) } }

                Group(s.playerSubtitles)
                // One row saying what is set; the preview and the three choices open over the page
                // (SubtitleStyleDialog), as on the phone and the TV.
                Setting(
                    s.settingsSubtitleStyle,
                    listOf(
                        subtitleStyle.size.label,
                        if (subtitleStyle.box) s.settingsSubtitleStyleBox else s.settingsSubtitleStyleNoBox,
                        subtitleStyle.position.label,
                    ).joinToString("  ·  "),
                ) {
                    OutlinedButton(onClick = { stylingSubtitles = true }) { Text(s.commonChange) }
                }

                OnlineSubtitlesGroup()
                OnlineMetadataGroup()

                Group(s.settingsLibrary)
                Toggle(s.settingsOpenLast, s.settingsOpenLastDetail, openLast) {
                    scope.launch { settings.setOpenLastChat(it) }
                }
                if (openLast && lastChatId != 0L) {
                    Setting(s.settingsForgetLast, s.settingsForgetLastDetail) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                settings.forgetLastChat()
                                // The row goes away on success, so the toast is the only sign.
                                toast(s.settingsForgetLastDone)
                            }
                        }) { Text(s.settingsForget) }
                    }
                }
                // A Column, not a Box: in a Box the two rows drew over each other.
                Column(Modifier.bringIntoViewRequester(sizeLimits)) {
                Setting(s.settingsSmallest, SizeFilter.label(minSize)) {
                    Stepper(
                        onLess = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, -1), maxSize)) } },
                        onMore = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, 1), maxSize)) } },
                    )
                }
                Setting(s.settingsLargest, SizeFilter.label(maxSize)) {
                    Stepper(
                        onLess = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, -1), minSize)) } },
                        onMore = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, 1), minSize)) } },
                    )
                }
                }
                // Reset means the defaults, as on the phone and TV: offering it at the defaults and
                // then clearing both ends contradicted the 50 MB the filter was still applying.
                if (!SizeFilter.isDefault(minSize, maxSize)) {
                    Setting(
                        s.settingsSizeLimitsReset,
                        s.settingsSizeLimitsResetDetail(SizeFilter.describe(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)),
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                // Widen first, so the clamp that stops the ends crossing cannot
                                // refuse the new floor on its way past the old ceiling.
                                settings.setMaxSizeBytes(SizeFilter.DEFAULT_MAX)
                                settings.setMinSizeBytes(SizeFilter.DEFAULT_MIN)
                            }
                        }) { Text(s.commonReset) }
                    }
                }

                StorageGroup(state)

                Group(s.settingsHistory)
                Setting(s.continueTitle, s.settingsContinueDetail) {
                    OutlinedButton(onClick = {
                        if (history.isEmpty()) {
                            toast(s.settingsContinueEmpty)
                            return@OutlinedButton
                        }
                        confirm = Confirm(
                            title = s.settingsContinueClearTitle,
                            message = s.settingsContinueClearMessage,
                            detail = s.settingsContinueClearDetail,
                        ) {
                            scope.launch {
                                val count = history.size
                                settings.clearWatchHistory()
                                toast(s.settingsContinueCleared(count))
                            }
                        }
                    }) { Text(s.commonClear) }
                }
                Setting(
                    s.settingsClearWatched,
                    s.settingsClearWatchedSummary(watchedList.size),
                ) {
                    OutlinedButton(onClick = {
                        if (watchedList.isEmpty()) toast(s.settingsWatchedEmpty) else confirmClearWatched = true
                    }) { Text(s.commonClear) }
                }
                Setting(s.navFavourites, s.settingsFavouritesDetail) {
                    OutlinedButton(onClick = {
                        if (favourites.isEmpty()) {
                            toast(s.settingsFavouritesEmpty)
                            return@OutlinedButton
                        }
                        confirm = Confirm(
                            title = s.settingsFavouritesClearTitle,
                            message = s.settingsFavouritesClearMessage(favourites.size),
                            detail = s.settingsFavouritesClearDetail,
                        ) {
                            scope.launch {
                                val count = favourites.size
                                settings.clearFavorites()
                                toast(s.settingsFavouritesCleared(count))
                            }
                        }
                    }) { Text(s.commonClear) }
                }

                state.extras.updates?.let { updates ->
                    Group(s.settingsUpdates)
                    Toggle(
                        s.settingsNotifyUpdates,
                        s.settingsNotifyUpdatesDetail,
                        notifyUpdates,
                    ) { on -> scope.launch { settings.setUpdateNotify(on) } }
                    val offered = updateState.release
                    if (offered != null) {
                        Setting(UpdateWords.settingsRow(offered), UpdateWords.youHave(version), titleColor = Tone.caution) {
                            OutlinedButton(onClick = { state.updatePopup = true }) { Text(s.commonOpen, color = Tone.caution) }
                        }
                    }
                    Setting(s.settingsCheckUpdates, s.settingsAppVersion(version)) {
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
                                    else -> toast(s.settingsUpToDate(version))
                                }
                            }
                        }) { Text(if (checking) s.settingsChecking else s.settingsCheck) }
                    }
                }

                Group(s.settingsHelp)
                Setting(s.settingsWalkthrough, s.settingsWalkthroughBody) {
                    OutlinedButton(onClick = { scope.launch { settings.replayOverview() } }) { Text(s.commonShow) }
                }
                Setting(s.settingsWhatsNew, s.settingsWhatsNewDetail(WhatsNew.VERSION)) {
                    OutlinedButton(onClick = { whatsNew = true }) { Text(s.commonShow) }
                }
                Setting(s.settingsChangelog, s.settingsChangelogDetail) {
                    OutlinedButton(onClick = { OpenExternal.browse(WhatsNew.CHANGELOG) }) { Text(s.commonOpen) }
                }
                Setting(s.settingsReportProblem, s.settingsReportProblemDetail) {
                    OutlinedButton(onClick = { reporting = true }) { Text(s.commonOpen) }
                }
                Setting(s.settingsPrivacy, s.settingsPrivacyComputer) {
                    OutlinedButton(onClick = { OpenExternal.browse(About.PRIVACY) }) { Text(s.commonOpen) }
                }
                Setting(s.settingsLawfulUse, s.settingsLawfulUseDetail) {
                    OutlinedButton(onClick = { OpenExternal.browse(About.LEGAL) }) { Text(s.commonOpen) }
                }
                if (SupportReminder.enabled) {
                    Setting(if (supportCounters.supporter) About.SUPPORT_THANKS_TITLE else About.SUPPORT_TITLE, s.settingsSupportDetail) {
                        OutlinedButton(onClick = { supporting = true }) { Text(s.commonShow) }
                    }
                }
                Setting(s.settingsAbout, s.settingsAboutDetail(version)) {
                    OutlinedButton(onClick = { showAbout = true }) { Text(s.commonOpen) }
                }

                Group(s.settingsAccount)
                Setting(s.settingsSignOut, s.settingsSignOutLoses) {
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text(s.signoutConfirm, color = Tone.danger) }
                }
                Text(
                    s.settingsPrivacyNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    if (confirmSignOut) SignOutDialog(state, onDismiss = { confirmSignOut = false })
    if (supporting) SupportPopup(from = "settings", onClose = { supporting = false })
    if (pickingLanguage) LanguagePopup(settings, onClose = { pickingLanguage = false })
    if (stylingSubtitles) SubtitleStyleDialog(settings, subtitleStyle, onClose = { stylingSubtitles = false })
    if (whatsNew) WhatsNewPopup(onClose = { whatsNew = false })
    if (reporting) FeedbackPopup(onClose = { reporting = false })
    if (confirmClearWatched) {
        ClearWatchedDialog(state, watchedList.size, scope, onDismiss = { confirmClearWatched = false })
    }

    confirm?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            detail = asked.detail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirm = null
                asked.action()
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * A small stand in for the picture, with a line drawn the way mpv will draw it: mpv's font size is
 * in pixels of a 720 line frame and its `sub-pos` is the share of the height the line's foot sits
 * at, above a 22 pixel margin, so both scale straight onto the preview's height.
 */
@Composable
private fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    BoxWithConstraints(
        modifier.width(360.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1A1F26)))),
    ) {
        val frame = maxHeight
        val fontSize = with(LocalDensity.current) { (frame * (style.size.mpvFontSize / 720f)).toSp() }
        val foot = frame * (1f - style.position.mpvSubPos / 100f) + frame * (22f / 720f)
        val text = TextStyle(
            color = Color.White,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            // mpv's outline, near enough; the box replaces it, as mpv's does.
            shadow = if (style.box) null else Shadow(Color.Black, blurRadius = 3f),
        )
        Text(
            s.subtitlesPreview,
            style = text,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = foot)
                .then(if (style.box) Modifier.background(Color(0xB3000000)).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier),
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
    val s = LocalStrings.current
    Row {
        IconButton(onClick = onLess) { Icon(TmIcons.Remove, contentDescription = s.commonLess) }
        IconButton(onClick = onMore) { Icon(Icons.Filled.Add, contentDescription = s.commonMore) }
    }
}

/**
 * Settings, Subtitle style, on the desktop: the preview at the top, where each change shows the
 * moment it is made, and the size, the background box and the position under it. Each is saved as
 * it changes; Close leaves.
 */
@Composable
private fun SubtitleStyleDialog(settings: SettingsStore, subtitleStyle: SubtitleStyle, onClose: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.settingsSubtitleStyle) },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                SubtitlePreview(subtitleStyle, Modifier.padding(bottom = 8.dp))
                Setting(s.subtitlesSize, s.settingsSubtitleSizeHint(subtitleStyle.size.label)) {
                    val sizes = SubtitleSize.entries
                    fun size(by: Int) {
                        val next = sizes[(subtitleStyle.size.ordinal + by).coerceIn(0, sizes.lastIndex)]
                        scope.launch { settings.setSubtitleStyle(subtitleStyle.copy(size = next)) }
                    }
                    Stepper(onLess = { size(-1) }, onMore = { size(1) })
                }
                Toggle(s.subtitlesBox, s.settingsSubtitleBoxDetail, subtitleStyle.box) { on ->
                    scope.launch { settings.setSubtitleStyle(subtitleStyle.copy(box = on)) }
                }
                Setting(s.subtitlesPosition, s.settingsSubtitlePositionHint) {
                    SingleChoiceSegmentedButtonRow {
                        SubtitlePosition.entries.forEachIndexed { index, place ->
                            SegmentedButton(
                                selected = subtitleStyle.position == place,
                                onClick = { scope.launch { settings.setSubtitleStyle(subtitleStyle.copy(position = place)) } },
                                shape = SegmentedButtonDefaults.itemShape(index, SubtitlePosition.entries.size),
                            ) { Text(place.label) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}
