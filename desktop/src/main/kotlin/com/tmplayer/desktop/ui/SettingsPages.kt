package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.BackupRead
import com.tmplayer.data.BackupService
import com.tmplayer.data.BackupWords
import com.tmplayer.data.LogExport
import com.tmplayer.data.ResumeRewind
import com.tmplayer.data.RestorePlan
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.release
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.desktop.os.DesktopLog
import com.tmplayer.desktop.os.FilePicker
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.components.jumpTarget
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.lock.AppLockWords
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberUiScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The rows of each settings page, drawn inside the page's SettingsColumn, in the phone's order
 * with the desktop's own rows where the phone has its touch ones.
 */

/** Appearance and language: the theme, the language, the interface size and the window's title bar. */
@Composable
internal fun AppearanceRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val accent by settings.accent.collectAsState(initial = com.tmplayer.data.Accent.Default)
    val customAccent by settings.customAccent.collectAsState(initial = null)
    val typeface by settings.typeface.collectAsState(initial = com.tmplayer.data.AppTypeface.Default)
    val highContrast by settings.highContrast.collectAsState(initial = false)
    var undo by remember { mutableStateOf<com.tmplayer.data.AppearanceSnapshot?>(null) }
    androidx.compose.runtime.LaunchedEffect(undo) {
        if (undo != null) {
            kotlinx.coroutines.delay(10_000L)
            undo = null
        }
    }
    val language by settings.language.collectAsState(initial = "")
    val uiScale by rememberUiScale(settings)
    val compactNav by settings.compactNav.collectAsState(initial = false)
    val desktop by state.extras.prefs.state.collectAsState()
    var pickingLanguage by remember { mutableStateOf(false) }

    // The choices below, live: a card in the theme, colour, typeface and contrast as they stand.
    Setting(s.settingsAppearancePreview, "") {
        com.tmplayer.ui.components.AppearancePreview(Modifier.widthIn(max = 420.dp))
    }
    Setting(
        s.settingsTheme,
        if (theme == ThemeChoice.System) s.themeSystemDesktopDetail else theme.description,
        icon = TmIcons.Theme,
    ) {
        Segments(ThemeChoice.entries, theme, { it.label }) { scope.launch { settings.setThemeChoice(it) } }
    }
    // Pure black, the usual switch under the theme: offered under System and Dark, folded away
    // under Light, where it would change nothing.
    com.tmplayer.ui.components.RevealUnder(theme.offersPureBlack, Modifier.layoutId(SettingsPart.Row)) {
        Toggle(s.settingsPureBlack, s.themeBlackDetail, pureBlack, icon = TmIcons.PureBlack) { on ->
            scope.launch { settings.setPureBlack(on) }
        }
    }
    // The accent colour as swatches, each named by its tooltip and to a screen reader.
    Setting(s.settingsAccent, s.settingsAccentDetail, icon = TmIcons.Accent) {
        com.tmplayer.ui.components.AccentSwatches(
            accent,
            { picked -> scope.launch { settings.setAccent(picked) } },
            size = 26.dp,
            custom = customAccent,
            onCustom = { argb -> scope.launch { settings.setCustomAccent(argb) } },
        )
    }
    Setting(s.settingsTypeface, s.settingsTypefaceDetail, icon = TmIcons.TextSize) {
        Segments(com.tmplayer.data.AppTypeface.entries, typeface, { it.label }) { scope.launch { settings.setTypeface(it) } }
    }
    Toggle(s.settingsHighContrast, s.settingsHighContrastDetail, highContrast, icon = TmIcons.Theme) { on ->
        scope.launch { settings.setHighContrast(on) }
    }
    Action(s.settingsLanguage, s.languageRowDetail(language), TmIcons.Language) { pickingLanguage = true }
    // The big-screen mode for a computer on a television (plan 8.7). Auto stays the desktop's: a
    // computer cannot tell a television from a monitor, so the viewer, or `--tv`, says so.
    Setting(s.settingsLayout, s.settingsLayoutDetailDesktop, icon = TmIcons.Tv) {
        Segments(DesktopLayout.entries, BigScreen.override ?: desktop.layout, { it.label }) { picked -> pickLayout(state, picked) }
    }
    Setting(s.interfaceSize, s.interfaceSizeDetail, icon = TmIcons.TextSize) {
        Segments(UiScale.entries, uiScale, { it.label }) { scope.launch { settings.setUiScale(it) } }
    }
    Toggle(
        s.settingsStartFullscreen,
        if (desktop.startFullscreen) s.settingsStartFullscreenOn else s.settingsStartFullscreenOff,
        desktop.startFullscreen,
        icon = TmIcons.Fullscreen,
    ) { on -> state.extras.prefs.update { it.copy(startFullscreen = on) } }
    Toggle(
        s.settingsCompactNav,
        if (compactNav) s.settingsCompactNavOn("desktop") else s.settingsCompactNavOff("desktop"),
        compactNav,
        icon = TmIcons.Grid,
    ) { on -> scope.launch { settings.setCompactNav(on) } }
    // Only where TMPlayer would draw window buttons of its own: Windows, and a tiling window
    // manager. Read at launch, so the change waits for a restart.
    if (remember { com.tmplayer.desktop.os.WindowChromeMode.current(systemTitleBar = false).inApp }) {
        Toggle(
            s.settingsSystemTitleBar,
            if (desktop.systemTitleBar) s.settingsSystemTitleBarOn else s.settingsSystemTitleBarOff,
            desktop.systemTitleBar,
            icon = TmIcons.WindowMaximize,
        ) { on -> state.extras.prefs.update { it.copy(systemTitleBar = on) } }
    }

    // Back to the defaults, with Undo in its place for a few seconds after: one row, so the
    // keyboard stays on it through both presses.
    val taken = undo
    Action(
        if (taken == null) s.settingsAppearanceDefaults else s.settingsAppearanceUndo,
        if (taken == null) s.settingsAppearanceDefaultsDetail else s.settingsAppearanceDefaultsDone,
        TmIcons.Refresh,
    ) {
        if (taken == null) {
            scope.launch { undo = settings.resetAppearance() }
        } else {
            undo = null
            scope.launch { settings.restoreAppearance(taken) }
        }
    }

    if (pickingLanguage) LanguagePopup(settings, onClose = { pickingLanguage = false })
}

/**
 * A layout picked in Settings: kept, in place of any `--tv` or `--desktop` this run started with,
 * and applied at once. Into the TV layout the window goes full screen, as a television's app is;
 * out of it, back to a window.
 */
internal fun pickLayout(state: ShellState, picked: DesktopLayout) {
    val wasTv = BigScreen.on
    state.extras.prefs.update { it.copy(layout = picked) }
    BigScreen.override = null
    BigScreen.layout = picked
    val tv = BigScreen.on
    if (tv && !wasTv) state.fullscreen = true
    if (!tv && wasTv) state.fullscreen = false
}

/**
 * Playback: what plays next and how, the sound, the decoding and the controls, in the phone's
 * order. Low storage mode is on the Storage page.
 */
@Composable
internal fun PlaybackRows(state: ShellState, jumps: Map<SetupStep, JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val autoplay by settings.autoplayNext.collectAsState(initial = true)
    val detailFirst by settings.detailFirst.collectAsState(initial = true)
    val downloadFirst by settings.downloadBeforePlaying.collectAsState(initial = false)
    val trackPrefs by settings.trackPreferences.collectAsState(initial = TrackPreferences())
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val resumeRewindMs by settings.resumeRewindMs.collectAsState(initial = ResumeRewind.DEFAULT_MS)
    val desktop by state.extras.prefs.state.collectAsState()

    Toggle(s.settingsAutoplay, if (autoplay) s.settingsAutoplayOn else s.settingsAutoplayOff, autoplay, icon = TmIcons.Play) {
        scope.launch { settings.setAutoplayNext(it) }
    }
    Toggle(
        s.settingsDetailFirst,
        if (detailFirst) s.settingsDetailFirstClickOn else s.settingsDetailFirstClickOff,
        detailFirst,
        icon = TmIcons.Info,
    ) { on -> scope.launch { settings.setDetailFirst(on) } }
    Toggle(s.settingsDownloadFirst, if (downloadFirst) s.settingsDownloadFirstOn else s.settingsDownloadFirstOff, downloadFirst, icon = TmIcons.Download) {
        scope.launch { settings.setDownloadBeforePlaying(it) }
    }
    // Low storage mode is on the Storage page, with the rest of what decides the room TMPlayer
    // takes (user, 2026-10-22).
    Toggle(s.settingsDownmix, if (desktop.downmix) s.settingsDownmixOn else s.settingsDownmixOff, desktop.downmix, icon = TmIcons.VolumeUp) { on ->
        state.extras.prefs.update { it.copy(downmix = on) }
    }
    val evenVolume by settings.evenVolume.collectAsState(initial = true)
    Toggle(s.playerEvenVolume, if (evenVolume) s.settingsEvenVolumeOn else s.settingsEvenVolumeOff, evenVolume, icon = TmIcons.VolumeUp) { on ->
        scope.launch { settings.setEvenVolume(on) }
    }
    AudioLanguageSetting(settings, trackPrefs, scope)
    Toggle(
        s.settingsSoftwareDecoding,
        if (DesktopSettings.defaultSoftwareDecoding()) s.settingsSoftwareDecodingLinux else s.settingsSoftwareDecodingDetail,
        desktop.softwareDecoding,
        icon = TmIcons.Frames,
    ) { on -> state.extras.prefs.update { it.copy(softwareDecoding = on) } }
    run {
        val choices = TouchPrefs.TIMEOUT_CHOICES_MS
        Setting(
            s.settingsHideControls,
            s.settingsHideControlsDetail(TouchPrefs.timeoutLabel(touchPrefs.controlsTimeoutMs)),
            icon = TmIcons.VisibilityOff,
        ) {
            Stepper(
                onLess = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, -1)) } } },
                onMore = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, 1)) } } },
                canLess = touchPrefs.controlsTimeoutMs != choices.first(),
                canMore = touchPrefs.controlsTimeoutMs != choices.last(),
            )
        }
    }
    // Four short choices, so all of them at once, as the theme row has them.
    Setting(s.settingsResumeRewind, s.settingsResumeRewindDetail, icon = TmIcons.History) {
        Segments(ResumeRewind.CHOICES_MS, resumeRewindMs, { ResumeRewind.label(it) }) {
            scope.launch { settings.setResumeRewindMs(it) }
        }
    }
}

/** Subtitles: how they look, when they come on and in which language, then Online subtitles. */
@Composable
internal fun SubtitlesRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val subtitleStyle by settings.subtitleStyle.collectAsState(initial = SubtitleStyle())
    val trackPrefs by settings.trackPreferences.collectAsState(initial = TrackPreferences())
    var styling by remember { mutableStateOf(false) }

    // One row saying what is set; the preview and the three choices open over the page
    // (SubtitleStyleDialog), as on the phone and the TV.
    Action(
        s.settingsSubtitleStyle,
        subtitleStyle.summary(),
        TmIcons.Subtitles,
    ) { styling = true }
    SubtitleLanguageSettings(settings, trackPrefs, scope)
    LearntLanguagesSetting(settings, scope)
    OnlineSubtitlesGroup()

    if (styling) SubtitleStyleDialog(settings, subtitleStyle, onClose = { styling = false })
}

/**
 * The mouse in the player, where the phone has Player gestures: holding the left button to play
 * fast (the phone's Hold to speed up, the same setting, so a change on either reaches both) and
 * what the wheel does.
 */
@Composable
internal fun MouseRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val desktop by state.extras.prefs.state.collectAsState()

    Setting(s.settingsHoldSpeed, s.settingsHoldSpeedDetailDesktop, icon = SettingsGlyphs.Faster) {
        Segments(TouchPrefs.HOLD_CHOICES, touchPrefs.holdSpeed, { TouchPrefs.holdLabel(it) }) { choice ->
            scope.launch { settings.updateTouchPrefs { it.copy(holdSpeed = choice) } }
        }
    }
    Toggle(
        s.settingsWheelSeeks,
        if (desktop.wheelSeeks) s.settingsWheelSeeksOn else s.settingsWheelSeeksOff,
        desktop.wheelSeeks,
        icon = SettingsGlyphs.Mouse,
    ) { on -> state.extras.prefs.update { it.copy(wheelSeeks = on) } }
}

/** A clear waiting on its yes: what the prompt says, and what happens on Clear. */
private class Confirm(val title: String, val message: String, val detail: String?, val action: () -> Unit)

/**
 * Library: which videos show, what opens on launch, the lists, the backup and Posters and
 * details, under the phone's headings.
 */
@Composable
internal fun LibraryRows(state: ShellState, version: String, chatList: List<com.tmplayer.data.ChatSummary>, jumps: Map<SetupStep, JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val minSize by settings.minSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MAX)
    val openLast by settings.openLastChat.collectAsState(initial = false)
    val homeCarousel by settings.homeCarousel.collectAsState(initial = true)
    val lastChatId by settings.lastChatId.collectAsState(initial = 0L)
    val rememberSearches by settings.rememberSearches.collectAsState(initial = true)
    val resumeSync by settings.resumeSync.collectAsState(initial = false)
    val newVideoAlerts by settings.newVideoAlerts.collectAsState(initial = false)
    val history by settings.continueWatching.collectAsState(initial = emptyList())
    // Only those the kids lock shows, as the Favourites section counts them.
    val storedFavourites by settings.favorites.collectAsState(initial = emptySet())
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsState()
    val favourites = remember(storedFavourites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavourites, allowedChats) }
    val watchedList by state.watched.history.collectAsState(initial = emptyList())
    // The chats hidden from their menus (see HiddenChats), listed below with Show.
    val hiddenChats by settings.hiddenChats.collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var confirmClearWatched by remember { mutableStateOf(false) }
    // A backup file picked, with what restoring it would change, while the prompt is up.
    var restorePlan by remember { mutableStateOf<RestorePlan?>(null) }
    val backups = remember(settings, state.watched, version) {
        BackupService(settings, state.watched, OnlineMetadata.current?.store, version, bookmarks = state.bookmarks)
    }

    Setting(s.settingsSmallest, SizeFilter.label(minSize), icon = TmIcons.FilterHorizontal) {
        Stepper(
            onLess = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, -1), maxSize)) } },
            onMore = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, 1), maxSize)) } },
        )
    }
    Setting(s.settingsLargest, SizeFilter.label(maxSize), icon = TmIcons.FilterHorizontal) {
        Stepper(
            onLess = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, -1), minSize)) } },
            onMore = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, 1), minSize)) } },
        )
    }
    // Reset means the defaults, as on the phone and TV: offering it at the defaults and then
    // clearing both ends contradicted the 50 MB the filter was still applying.
    if (!SizeFilter.isDefault(minSize, maxSize)) {
        Action(
            s.settingsSizeLimitsReset,
            s.settingsSizeLimitsResetDetail(SizeFilter.describe(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)),
            TmIcons.Refresh,
            value = s.commonReset,
            end = RowEnd.None,
        ) {
            scope.launch {
                // Widen first, so the clamp that stops the ends crossing cannot refuse the new
                // floor on its way past the old ceiling.
                settings.setMaxSizeBytes(SizeFilter.DEFAULT_MAX)
                settings.setMinSizeBytes(SizeFilter.DEFAULT_MIN)
            }
        }
    }

    // What opens first, in the same card as which videos show, as on the phone and the TV.
    val lastChatTitle = remember(chatList, lastChatId) { chatList.firstOrNull { it.id == lastChatId }?.title }
    Toggle(
        s.settingsOpenLastChat,
        when {
            lastChatTitle != null -> s.settingsOpenLastChatNamed(lastChatTitle)
            lastChatId != 0L -> s.settingsOpenLastChatUnnamed
            else -> s.settingsOpenLastChatOff
        },
        openLast,
        icon = TmIcons.History,
    ) { scope.launch { settings.setOpenLastChat(it) } }
    if (openLast && lastChatId != 0L) {
        Action(s.settingsForgetLastChat, s.settingsForgetLastChatDetail, TmIcons.Close, end = RowEnd.None) {
            scope.launch {
                settings.forgetLastChat()
                // The row goes away on success, so the toast is the only sign.
                toast(s.settingsForgotLastChat)
            }
        }
    }
    // The carousel at the top of Home, as on the phone and the TV.
    Toggle(
        s.settingsHomeCarousel,
        if (homeCarousel) s.settingsHomeCarouselOn else s.settingsHomeCarouselOff,
        homeCarousel,
        icon = TmIcons.Home,
    ) { scope.launch { settings.setHomeCarousel(it) } }

    // The lists TMPlayer keeps: recent searches, then a clear for each list with anything in it.
    Group(s.settingsLists)
    // Off forgets the searches already kept, which is what switching it off is for.
    Toggle(
        s.settingsRememberSearches,
        if (rememberSearches) s.settingsRememberSearchesOn else s.settingsRememberSearchesOff,
        rememberSearches,
        icon = TmIcons.Search,
    ) { on -> scope.launch { settings.setRememberSearches(on) } }
    // Resume across devices: opt in, the row saying what it writes and where (ResumeSync).
    Toggle(
        s.settingsResumeSync,
        if (resumeSync) s.settingsResumeSyncOn else s.settingsResumeSyncOff,
        resumeSync,
        icon = TmIcons.History,
    ) { on ->
        scope.launch {
            settings.setResumeSync(on)
            // The switch and its line say it is on; nothing more to say.
            if (on) com.tmplayer.data.ResumeSync.syncSoon(settings, afterMs = 0)
        }
    }
    // Hidden chats: each one with Show, and Show all for several; the empty list says how one gets here.
    if (hiddenChats.isEmpty()) {
        Setting(s.settingsHiddenChats, s.settingsHiddenChatsNone, icon = TmIcons.VisibilityOff) {}
    } else {
        Setting(s.settingsHiddenChats, s.settingsHiddenChatsNote, icon = TmIcons.VisibilityOff) {}
        hiddenChats.forEach { chat ->
            Action(chat.title.ifBlank { chat.id.toString() }, s.settingsHiddenChatOne, TmIcons.Chats, value = s.settingsHiddenChatsShow, end = RowEnd.None) {
                // The line leaving this list is the answer.
                scope.launch { settings.showChat(chat.id) }
            }
        }
        if (hiddenChats.size > 1) {
            Action(s.settingsHiddenChatsShowAll, s.settingsHiddenChatsDetail(hiddenChats.size), TmIcons.Checklist, end = RowEnd.None) {
                // The list emptying is the answer.
                scope.launch { settings.showAllChats() }
            }
        }
    }
    if (favourites.isNotEmpty()) Action(s.settingsClearFavourites, s.settingsClearFavouritesDetail(favourites.size), TmIcons.Close, end = RowEnd.None) {
        confirm = Confirm(
            title = s.settingsFavouritesClearTitle,
            message = s.settingsFavouritesClearMessage(favourites.size),
            detail = s.settingsFavouritesClearDetail,
        ) {
            scope.launch {
                val count = favourites.size
                settings.clearFavorites(allowedChats)
                toast(s.settingsFavouritesCleared(count))
            }
        }
    }
    if (history.isNotEmpty()) Action(s.settingsClearHistory, s.settingsClearHistoryDetail(history.size), TmIcons.Close, end = RowEnd.None) {
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
    }
    if (watchedList.isNotEmpty()) {
        Action(s.settingsClearWatched, s.settingsClearWatchedDetail(watchedList.size), TmIcons.Close, end = RowEnd.None) {
            confirmClearWatched = true
        }
    }

    // To a file and back: settings, lists and resume points, never the Telegram sign in.
    Group(s.backupSection)
    Action(s.backupBackUp, s.backupBackUpDetail, TmIcons.Download) {
        scope.launch {
            val file = FilePicker.save(s.backupSaveTitle, backups.fileName(), "json") ?: return@launch
            val saved = withContext(Dispatchers.IO) { runCatching { file.writeText(backups.export()) }.isSuccess }
            toast(if (saved) s.backupSavedTo(file.absolutePath) else s.backupFailed)
        }
    }
    Action(s.backupRestore, s.backupRestoreDetail, TmIcons.Refresh) {
        scope.launch {
            val file = FilePicker.open(s.backupPickTitle, "json") ?: return@launch
            val text = withContext(Dispatchers.IO) {
                // No backup comes near this; a video picked by mistake is not read whole.
                runCatching { file.takeIf { it.length() <= 32L * 1024 * 1024 }?.readText() }.getOrNull()
            }
            if (text == null) {
                toast(s.backupReadFailed)
                return@launch
            }
            when (val read = backups.read(text)) {
                is BackupRead.Ok -> {
                    val plan = backups.plan(read.backup)
                    if (plan.nothing) toast(s.backupRestoreNothing) else restorePlan = plan
                }
                else -> BackupWords.refusal(read)?.let { toast(it) }
            }
        }
    }

    OnlineMetadataGroup(Modifier.jumpTarget(jumps.getValue(SetupStep.Posters)))

    if (confirmClearWatched) {
        ClearWatchedDialog(state, watchedList.size, scope, onDismiss = { confirmClearWatched = false })
    }
    restorePlan?.let { plan ->
        ConfirmDialog(
            title = s.backupRestoreTitle,
            message = BackupWords.restoreMessage(plan),
            detail = s.backupRestoreUntouched,
            confirmLabel = s.backupRestoreConfirm,
            destructive = false,
            onConfirm = {
                restorePlan = null
                scope.launch {
                    val done = runCatching { backups.restore(plan.backup) }.isSuccess
                    toast(if (done) s.backupRestored else s.backupReadFailed)
                }
            },
            onDismiss = { restorePlan = null },
        )
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

/** App lock: the PIN, asking for it on open, the kids lock and its chats. */
@Composable
internal fun LockRows(state: ShellState, onPin: (DesktopPinFlow) -> Unit, onKidsChats: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val appLock by settings.appLock.collectAsState(initial = AppLockSettings())

    if (!appLock.pinSet) {
        Action(s.lockSetPin, s.lockSetPinDetail, TmIcons.Lock) { onPin(DesktopPinFlow.Set) }
    } else {
        Action(s.lockChangePin, s.lockChangePinDetail, TmIcons.Lock) { onPin(DesktopPinFlow.ChangeCheck) }
        Toggle(s.lockOnOpen, s.lockOnOpenDetail, appLock.onOpen, icon = TmIcons.VisibilityOff) { on ->
            // Whoever switches it on has just given the PIN to reach this page.
            if (on) AppLockSession.unlock()
            scope.launch { settings.setLockOnOpen(on) }
        }
    }
    Toggle(s.lockKids, AppLockWords.kidsDetail(appLock), appLock.kids, icon = TmIcons.Group) { on ->
        when {
            // The lock is only as good as the PIN that guards its switch.
            !appLock.pinSet -> onPin(DesktopPinFlow.SetForKids)
            !on -> scope.launch { settings.setKidsLock(false) }
            else -> {
                scope.launch { settings.setKidsLock(true) }
                if (appLock.kidsChats.isEmpty()) onKidsChats()
            }
        }
    }
    Action(s.lockAllowedChats, s.lockAllowedChatsDetail(count = appLock.kidsChats.size), TmIcons.List) { onKidsChats() }
    if (appLock.pinSet) {
        Action(s.lockRemovePin, s.lockRemovePinDetail, TmIcons.Close) { onPin(DesktopPinFlow.Remove) }
    }
    Text(
        s.lockForgot,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier.padding(horizontal = SETTING_INSET),
    )
    // Where the account is signed in, and ending a session (CP83): behind this page's PIN.
    Action(s.settingsDevices, s.settingsDevicesDetail, TmIcons.Phone) { com.tmplayer.ui.settings.DevicesScreenState.open = true }
}

/**
 * About and help: the version and its updates (only where this build updates itself), then help,
 * privacy and About TMPlayer, and the privacy note at the end, as the phone's page has them.
 */
@Composable
internal fun AboutRows(state: ShellState, version: String, onShowAbout: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val notifyUpdates by settings.updateNotify.collectAsState(initial = true)
    val testVersions by settings.updateTestVersions.collectAsState(initial = false)
    var askTestVersions by remember { mutableStateOf(false) }
    val updateState by Updates.state.collectAsState()
    val developerOptions by settings.developerOptions.collectAsState(initial = false)
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    var supporting by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    state.extras.updates?.let { updates ->
        Group(s.settingsVersion)
        updateState.release?.let { offered ->
            Action(
                UpdateWords.settingsRow(offered),
                UpdateWords.youHave(version),
                TmIcons.Refresh,
                titleColor = Tone.caution,
                iconTint = Tone.caution,
            ) { state.updatePopup = true }
        }
        var checking by remember { mutableStateOf(false) }
        Action(
            s.settingsCheckUpdates,
            s.settingsAppVersion(version),
            TmIcons.Refresh,
            value = if (checking) s.settingsChecking else null,
            end = RowEnd.None,
            enabled = !checking,
        ) {
            checking = true
            scope.launch {
                updates.checkNow()
                checking = false
                // Anything to offer opens the popup, a skipped version included; the rest is a
                // sentence.
                when (val after = Updates.state.value) {
                    is UpdateState.Available -> state.updatePopup = true
                    is UpdateState.Failed -> {
                        toast(after.message)
                        Updates.dismiss()
                    }
                    else -> toast(s.settingsUpToDate(version))
                }
            }
        }
        Toggle(s.settingsUpdateNotify, s.settingsUpdateNotifyDetail, notifyUpdates, icon = TmIcons.Bell) { on ->
            scope.launch { settings.setUpdateNotify(on) }
        }
        // GitHub pre-releases (RL1). On asks first, with what it means; off is not asked.
        Toggle(
            s.settingsTestVersions,
            if (testVersions) s.settingsTestVersionsOn else s.settingsTestVersionsOff,
            testVersions,
            icon = TmIcons.Warning,
        ) { on ->
            if (on) {
                askTestVersions = true
            } else {
                scope.launch {
                    settings.setUpdateTestVersions(false)
                    Updates.testVersionsSwitched(on = false, skipped = settings.updatePrefs.skippedVersion())
                }
            }
        }
    }
    if (askTestVersions) {
        ConfirmDialog(
            title = s.confirmTestVersionsTitle,
            message = s.confirmTestVersionsMessage,
            detail = s.confirmTestVersionsDetail,
            confirmLabel = s.confirmTurnOn,
            destructive = false,
            onConfirm = {
                askTestVersions = false
                scope.launch {
                    settings.setUpdateTestVersions(true)
                    Updates.testVersionsSwitched(on = true, skipped = settings.updatePrefs.skippedVersion())
                }
            },
            onDismiss = { askTestVersions = false },
        )
    }

    Group(s.settingsHelp)
    // A proxy (CP77), for a network where Telegram is blocked: the connecting help's own screen.
    val proxyOn by com.tmplayer.data.Td.proxyOn.collectAsState()
    Action(s.proxyTitle, if (proxyOn) s.proxyInUse else s.proxySettingsDetailOff, TmIcons.Wifi) {
        com.tmplayer.ui.connect.ProxyScreenState.open = true
    }
    Action(s.settingsTour, s.settingsTourBody, TmIcons.Help) { scope.launch { settings.openTour() } }
    Action(s.settingsWhatsNew, s.settingsWhatsNewDetail(WhatsNew.VERSION), TmIcons.StarOutline) { whatsNew = true }
    Action(s.settingsChangelog, s.settingsChangelogDetail, TmIcons.List, end = RowEnd.External) {
        OpenExternal.browse(WhatsNew.CHANGELOG)
    }
    Action(s.settingsReportProblem, s.settingsReportProblemDetail, TmIcons.Bug) { reporting = true }
    // The logs to attach to that report (CP59), scrubbed of names and ids before they are written.
    Action(s.logsSave, s.logsSaveDetail, TmIcons.Logs, end = RowEnd.None) {
        scope.launch {
            val lines = withContext(Dispatchers.IO) { DesktopLog.recentLines(LogExport.MAX_LINES) }
            if (lines.isEmpty()) {
                toast(s.logsEmpty)
                return@launch
            }
            val file = FilePicker.save(s.logsSaveTitle, LogExport.fileName(), "txt") ?: return@launch
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val known = LogExport.knownNames(settings, state.watched)
                    file.writeText(LogExport.report(desktopFeedbackFacts(), lines, known))
                }.isSuccess
            }
            toast(if (saved) s.logsSavedTo(file.absolutePath) else s.logsFailed)
        }
    }
    // Developers only (see DevOptions): how the last chat's file names were read.
    if (developerOptions || DevOptions.forced) {
        Action(s.logsNameReport, s.logsNameReportDetail, TmIcons.Checklist, end = RowEnd.None) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { saveNameCheck() } }
                file.onSuccess { toast(if (it == null) s.logsNameReportEmpty else s.logsNameReportSaved(it.absolutePath)) }
                    .onFailure { toast(s.logsFailed) }
            }
        }
    }
    Action(s.settingsPrivacy, s.settingsPrivacyComputer, TmIcons.Lock, end = RowEnd.External) {
        OpenExternal.browse(About.PRIVACY)
    }
    // The one setting that sends anything anywhere but Telegram and GitHub, off until it is asked
    // for (CP102g). A build with no DSN has nowhere to send a report, so the row is not drawn.
    if (com.tmplayer.desktop.DesktopCrashReports.available) {
        val crashReports by settings.crashReports.collectAsState(initial = false)
        Toggle(
            s.settingsCrashReports,
            if (crashReports) s.settingsCrashReportsOn else s.settingsCrashReportsOff,
            crashReports,
            icon = TmIcons.Warning,
        ) { on ->
            scope.launch {
                runCatching { settings.setCrashReports(on) }
                withContext(Dispatchers.IO) {
                    if (on) com.tmplayer.desktop.DesktopCrashReports.start(true) else com.tmplayer.desktop.DesktopCrashReports.stop()
                }
            }
        }
    }
    Action(s.settingsLawfulUse, s.settingsLawfulUseDetail, TmIcons.Gavel, end = RowEnd.External) {
        OpenExternal.browse(About.LEGAL)
    }
    if (SupportReminder.enabled) {
        Action(
            if (supportCounters.supporter) About.SUPPORT_THANKS_TITLE else About.SUPPORT_TITLE,
            s.settingsSupportDetail,
            TmIcons.Favorite,
        ) { supporting = true }
        // The occasional support notification's switch is on the Notifications page (CP76).
    }
    Action(s.settingsAbout, s.settingsAboutDetail(version), TmIcons.Info) { onShowAbout() }
    Text(
        s.settingsPrivacyNote,
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 12.dp),
    )

    if (supporting) SupportPopup(from = "settings", onClose = { supporting = false })
    if (whatsNew) WhatsNewPopup(onClose = { whatsNew = false })
    if (reporting) FeedbackPopup(onClose = { reporting = false })
}

/**
 * Notifications (CP76): every system notification TMPlayer may post, one switch each, the phone's
 * page with the desktop's wording. Off but for the finished downloads until the viewer turns one
 * on; the checks behind them run while the app is open, minimised too (DesktopChecks).
 */
@Composable
internal fun NotificationRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    val rows = buildList {
        add(Triple(com.tmplayer.data.Notice.Downloads, s.settingsAlertDownloads, s.settingsAlertDownloadsDetail))
        add(Triple(com.tmplayer.data.Notice.NewEpisodes, s.settingsAlertEpisodes, s.settingsAlertEpisodesDetailDesktop))
        add(Triple(com.tmplayer.data.Notice.NewVideos, s.settingsNewVideos, s.settingsNewVideosDetailDesktop))
        add(Triple(com.tmplayer.data.Notice.MissingPieces, s.settingsAlertPieces, s.settingsAlertPiecesDetail))
        add(Triple(com.tmplayer.data.Notice.LowStorage, s.settingsAlertLowStorage, s.settingsAlertLowStorageDetail))
        if (Updates.enabled) add(Triple(com.tmplayer.data.Notice.UpdateReady, s.settingsAlertUpdate, s.settingsAlertUpdateDetailDesktop))
        if (SupportReminder.enabled && !supportCounters.finished) {
            add(Triple(com.tmplayer.data.Notice.Support, s.settingsSponsorReminders, s.settingsSponsorRemindersDetail))
        }
    }
    for ((notice, title, detail) in rows) {
        val on by settings.noticeOn(notice).collectAsState(initial = notice.defaultOn)
        Toggle(title, detail, on, icon = TmIcons.Bell) { next ->
            scope.launch { settings.setNoticeOn(notice, next) }
        }
    }
}
