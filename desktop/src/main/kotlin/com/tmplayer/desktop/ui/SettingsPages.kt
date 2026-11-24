package com.tmplayer.desktop.ui

import com.tmplayer.ui.components.ToastKind
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
import com.tmplayer.platform.Background
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
    // Follow the system, On or Off (WP-50): the choice wins; a stored on counts as On. Choosing On or
    // Off also writes the shared flag, so a backup and the phone's row say the same; Follow leaves it.
    val reduceMotion by settings.reduceMotion.collectAsState(initial = false)
    val systemChoices = listOf<Boolean?>(null, true, false)
    val systemLabel: @Composable (Boolean?) -> String = { if (it == null) s.settingsFollowSystemContrast else if (it) s.commonOn else s.commonOff }
    Setting(s.settingsHighContrast, s.settingsHighContrastDetail, icon = TmIcons.Visibility) {
        Segments(systemChoices, SystemDefaults.shown(desktop.highContrast, highContrast), systemLabel) { picked ->
            state.extras.prefs.update { it.copy(highContrast = picked) }
            if (picked != null) scope.launch { settings.setHighContrast(picked) }
        }
    }
    Setting(s.settingsReduceMotion, if (SystemDefaults.effective(desktop.reduceMotion, reduceMotion, null)) s.settingsReduceMotionOn else s.settingsReduceMotionOff, icon = TmIcons.Visibility) {
        Segments(systemChoices, SystemDefaults.shown(desktop.reduceMotion, reduceMotion), systemLabel) { picked ->
            state.extras.prefs.update { it.copy(reduceMotion = picked) }
            if (picked != null) scope.launch { settings.setReduceMotion(picked) }
        }
    }
    Action(s.settingsLanguage, s.languageRowDetail(language), TmIcons.Language) { pickingLanguage = true }
    // The big-screen mode for a computer on a television (plan 8.7). Desktop or TV, no Auto: a
    // computer cannot tell a television from a monitor, so the viewer, or `--tv`, says so.
    Setting(s.settingsLayout, s.settingsLayoutDetailDesktop, icon = TmIcons.Tv) {
        Segments(DesktopLayout.entries, BigScreen.override ?: desktop.layout, { it.label }) { picked -> pickLayout(state, picked) }
    }
    Setting(s.interfaceSize, s.interfaceSizeDetail, icon = TmIcons.TextSize) {
        // The shared sizes and the desktop's own Huge (WP-50); Ctrl+= and Ctrl+- step them, Ctrl+0 resets.
        Segments(DesktopSize.entries, DesktopSize.of(uiScale, desktop.hugeSize), { it.label }) { picked ->
            state.extras.prefs.update { it.copy(hugeSize = picked == DesktopSize.Huge) }
            if (picked != DesktopSize.Huge) scope.launch { settings.setUiScale(picked.scale) }
        }
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
    // Open at sign in (WP-52): off until asked, read from the OS rather than stored.
    LaunchAtLoginRow()

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
    val playChatOrder by settings.playChatOrder.collectAsState(initial = false)
    val playAllSkipWatched by settings.playAllSkipWatched.collectAsState(initial = false)
    val keepRepeat by settings.keepRepeat.collectAsState(initial = false)
    val detailFirst by settings.detailFirst.collectAsState(initial = true)
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val resumeRewindMs by settings.resumeRewindMs.collectAsState(initial = ResumeRewind.DEFAULT_MS)
    val desktop by state.extras.prefs.state.collectAsState()

    Toggle(s.settingsAutoplay, if (autoplay) s.settingsAutoplayOn else s.settingsAutoplayOff, autoplay, icon = TmIcons.Play) {
        scope.launch { settings.setAutoplayNext(it) }
    }
    // After a video that is not an episode, and whether Repeat this video outlasts it (EG-F30, EG-F52).
    Toggle(s.settingsPlayChatOrder, if (playChatOrder) s.settingsPlayChatOrderOn else s.settingsPlayChatOrderOff, playChatOrder, icon = TmIcons.Layers) {
        scope.launch { settings.setPlayChatOrder(it) }
    }
    // Play all and Shuffle past what was watched (WP-32).
    Toggle(s.settingsPlayAllSkipWatched, if (playAllSkipWatched) s.settingsPlayAllSkipWatchedOn else s.settingsPlayAllSkipWatchedOff, playAllSkipWatched, icon = TmIcons.CheckCircle) {
        scope.launch { settings.setPlayAllSkipWatched(it) }
    }
    Toggle(s.settingsKeepRepeat, if (keepRepeat) s.settingsKeepRepeatOn else s.settingsKeepRepeatOff, keepRepeat, icon = TmIcons.Repeat) {
        scope.launch { settings.setKeepRepeat(it) }
    }
    Toggle(
        s.settingsDetailFirst,
        if (detailFirst) s.settingsDetailFirstClickOn else s.settingsDetailFirstClickOff,
        detailFirst,
        icon = TmIcons.Info,
    ) { on -> scope.launch { settings.setDetailFirst(on) } }
    // Low storage mode and Download the whole video first are on Storage and downloads, with the
    // rest of what decides the room TMPlayer takes (user, 2026-10-22; U20); the sound and the
    // audio language are on Subtitles and audio.
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
    // A TV that discovery never finds: the same dialog as the player's Cast menu, reached without starting a cast.
    var addingTv by remember { mutableStateOf(false) }
    Action(s.castAddEntry, s.settingsAddTvDetail, TmIcons.Tv) { addingTv = true }
    if (addingTv) {
        val saved by com.tmplayer.desktop.cast.DesktopCast.discovery.saved.collectAsState()
        com.tmplayer.ui.components.AddTvDialog(
            saved = saved,
            onAdd = com.tmplayer.desktop.cast.DesktopCast.discovery::add,
            onRemove = com.tmplayer.desktop.cast.DesktopCast.discovery::remove,
            onDismiss = { addingTv = false },
        )
    }
    // The mouse where the phone has its gestures, then the decoder choice last, under Advanced:
    // a first screen with "Force software decoding" on it read as jargon (Raju pass 2, 11.3).
    Group(s.settingsPageMouse)
    MouseRows(state)
    Group(s.settingsAdvanced)
    Toggle(
        s.settingsSoftwareDecoding,
        if (DesktopSettings.defaultSoftwareDecoding()) s.settingsSoftwareDecodingLinux else s.settingsSoftwareDecodingDetail,
        desktop.softwareDecoding,
        icon = TmIcons.Frames,
    ) { on -> state.extras.prefs.update { it.copy(softwareDecoding = on) } }
}

/**
 * Subtitles and audio: how subtitles look, when they come on and in which language, the
 * soundtrack's language beside them (one set of track languages, U20), the sound, then Online
 * subtitles.
 */
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
    AudioLanguageSetting(settings, trackPrefs, scope)
    LearntLanguagesSetting(settings, scope)
    // The sound (moved from Playback by U20), under the soundtrack's language.
    Group(s.settingsSound)
    val desktop by state.extras.prefs.state.collectAsState()
    Toggle(s.settingsDownmix, if (desktop.downmix) s.settingsDownmixOn else s.settingsDownmixOff, desktop.downmix, icon = TmIcons.VolumeUp) { on ->
        state.extras.prefs.update { it.copy(downmix = on) }
    }
    val evenVolume by settings.evenVolume.collectAsState(initial = true)
    Toggle(s.playerEvenVolume, if (evenVolume) s.settingsEvenVolumeOn else s.settingsEvenVolumeOff, evenVolume, icon = TmIcons.VolumeUp) { on ->
        scope.launch { settings.setEvenVolume(on) }
    }
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
    // Esc leaves the video (WP-47): on as it always was; off keeps a stray Esc from closing one.
    Toggle(
        s.settingsEscLeavesVideo,
        if (desktop.escLeavesVideo) s.settingsEscLeavesVideoOn else s.settingsEscLeavesVideoOff,
        desktop.escLeavesVideo,
        icon = SettingsGlyphs.Mouse,
    ) { on -> state.extras.prefs.update { it.copy(escLeavesVideo = on) } }
}

/**
 * A clear waiting on its yes: what the prompt says, and what happens on Clear. [confirmLabel] and
 * [destructive] for a prompt that is not a clear (resume on other devices' turn on and delete).
 */
private class Confirm(
    val title: String,
    val message: String,
    val detail: String?,
    val confirmLabel: String? = null,
    val destructive: Boolean = true,
    val action: () -> Unit,
)

/**
 * Library: which videos show, what opens on launch, the lists and Posters and details, under the
 * phone's headings. The backup is on About and help since 2026-11-01 ([AboutRows]).
 */
@Composable
internal fun LibraryRows(state: ShellState, chatList: List<com.tmplayer.data.ChatSummary>, jumps: Map<SetupStep, JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val openLast by settings.openLastChat.collectAsState(initial = false)
    val lastChatId by settings.lastChatId.collectAsState(initial = 0L)
    val rememberSearches by settings.rememberSearches.collectAsState(initial = true)
    val newVideoAlerts by settings.newVideoAlerts.collectAsState(initial = false)
    val history by settings.continueWatching.collectAsState(initial = emptyList())
    // Only those the kids lock shows, as the Favourites section counts them.
    val storedFavourites by settings.favorites.collectAsState(initial = emptySet())
    val allowedChats by com.tmplayer.data.ChatGate.browsing.collectAsState()
    val favourites = remember(storedFavourites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavourites, allowedChats) }
    val watchedList by state.watched.history.collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val historyCards = historyCardCount(state)
    val clearHistory = rememberClearAllConfirm(
        count = historyCards,
        title = s.browseClearHistoryTitle,
        message = s.browseClearHistoryMessage(historyCards),
        detail = s.browseClearHistoryDetail,
    ) {
        runHistoryEdit(scope, state, undoToast, toast, { s.browseHistoryCleared }, s.browseUndo) { st, w -> com.tmplayer.data.HistoryEdits.clear(st, w) }
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
                toast(s.settingsForgotLastChat, ToastKind.Success)
            }
        }
    }
    // The carousel and Home's other rows are on Settings' Home page (HomeLayoutRows).

    // The lists TMPlayer keeps: recent searches, then a clear for each list with anything in it.
    Group(s.settingsLists)
    // Off forgets the searches already kept, which is what switching it off is for.
    Toggle(
        s.settingsRememberSearches,
        if (rememberSearches) s.settingsRememberSearchesOn else s.settingsRememberSearchesOff,
        rememberSearches,
        icon = TmIcons.Search,
    ) { on -> scope.launch { settings.setRememberSearches(on) } }
    // The picker: the chats added decide what Chats, Home and search show (HX27). The one list of
    // which chats TMPlayer shows: a chat's "Remove from TMPlayer" unticks a chat here (2026-11-01).
    val added by com.tmplayer.data.AddedChats.choice.collectAsState()
    Action(
        s.pickerRowTitle,
        com.tmplayer.ui.browse.chosenChatsDetail(added, s),
        TmIcons.ChatsMany,
    ) { com.tmplayer.ui.browse.AddChatsState.open = true }
    if (favourites.isNotEmpty()) Action(s.settingsClearFavourites, s.settingsClearFavouritesDetail(favourites.size), TmIcons.Close, end = RowEnd.None) {
        confirm = Confirm(
            title = s.settingsFavouritesClearTitle,
            message = s.settingsFavouritesClearMessage(favourites.size),
            detail = s.settingsFavouritesClearDetail,
        ) {
            scope.launch {
                val count = favourites.size
                settings.clearFavorites(allowedChats)
                toast(s.settingsFavouritesCleared(count), ToastKind.Success)
            }
        }
    }
    // One Clear history, as History's own: Continue watching and Watched together, Watch later
    // kept, asked first (HX59) and said with Undo.
    if (history.isNotEmpty() || watchedList.isNotEmpty()) {
        Action(s.browseClearHistory, s.browseClearHistoryDetail, TmIcons.Close, end = RowEnd.None, onClick = clearHistory)
    }

    OnlineMetadataGroup(Modifier.jumpTarget(jumps.getValue(SetupStep.Posters)))

    confirm?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            detail = asked.detail,
            confirmLabel = asked.confirmLabel ?: s.commonClear,
            destructive = asked.destructive,
            onConfirm = {
                confirm = null
                asked.action()
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * Account and security (2026-11-01): App lock (the PIN, asking for it on open, the kids lock and
 * its chats), then where the account is signed in and Sign out, which were a row and a button of
 * their own on the list before.
 */
@Composable
internal fun PrivacyRows(state: ShellState, onPin: (DesktopPinFlow) -> Unit, onKidsChats: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val appLock by settings.appLock.collectAsState(initial = AppLockSettings())

    Group(s.lockTitle)
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
    // What leaves this computer and what the privacy page says about it (moved from About and help
    // by U20): the one switch that sends anything, beside the page explaining it.
    Group(s.settingsPrivacy)
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
}

/**
 * Account (WP-08): who is signed in, where (the devices), keeping the place on every device, the
 * profiles on this computer and Sign out, which asks first. The page is behind Settings' PIN, so a
 * profile's sign out stays behind it too.
 */
@Composable
internal fun AccountRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    Group(s.settingsAccountSignedIn)
    // Where the account is signed in, and ending a session (CP83): behind this page's PIN.
    Action(s.settingsDevices, s.settingsDevicesDetail, TmIcons.Phone) { com.tmplayer.ui.settings.DevicesScreenState.open = true }
    // Keep my place on all my devices (FQ12, moved here from Library): the one switch for the sync
    // file in the viewer's own Saved Messages, the privacy line as its words. Turning it on is the
    // answer to the question, so it asks nothing more; off keeps the file, which the row below
    // takes away.
    val toast = rememberToast()
    val resumeSync by settings.resumeSync.collectAsState(initial = false)
    val resumeSyncStatus by settings.resumeSyncStatus.collectAsState(initial = null)
    val resumeSyncAt by settings.resumeSyncAt.collectAsState(initial = 0L)
    val resumeSyncRunning by com.tmplayer.data.ResumeSync.running.collectAsState()
    var syncConfirm by remember { mutableStateOf<Confirm?>(null) }
    Toggle(s.syncplaceSwitch, if (resumeSync) s.syncplaceSwitchOn else s.syncplaceSwitchOff, resumeSync, icon = TmIcons.History) { on ->
        if (on) {
            scope.launch {
                settings.setResumeSync(true)
                settings.setKeepPlaceCardSeen()
                com.tmplayer.data.ResumeSync.syncSoon(settings, afterMs = 0)
            }
            toast(s.syncplaceStarted)
        } else {
            scope.launch { settings.setResumeSync(false) }
        }
    }
    // What goes into the file, in one plain line, in full under the switch.
    Text(
        s.syncplacePrivacy,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier.padding(horizontal = SETTING_INSET),
    )
    // Where the file and the backups are kept: Saved Messages, or a private channel of the viewer's
    // own with only them in it (HX87). Its page has the guide.
    val syncChannel by settings.syncChannel.collectAsState(initial = null)
    val syncChannelBlock by settings.syncChannelBlock.collectAsState(initial = null)
    Action(
        s.syncchannelRowTitle,
        com.tmplayer.ui.settings.syncChannelRowLine(s, syncChannel, syncChannelBlock),
        TmIcons.Account,
    ) { com.tmplayer.ui.settings.SyncChannelScreenState.open = true }
    if (resumeSync) {
        // Sync now, with how the last sync went under it; Replace when the file there could not
        // be read, which asks first. Run in the app's own scope, so leaving Settings does not
        // stop it half way.
        Action(
            com.tmplayer.data.SyncWords.action(resumeSyncStatus),
            com.tmplayer.data.SyncWords.status(resumeSyncStatus, resumeSyncAt, resumeSyncRunning),
            TmIcons.Refresh,
            end = RowEnd.None,
            enabled = !resumeSyncRunning,
        ) {
            if (resumeSyncStatus?.kind == com.tmplayer.data.SyncStatus.Kind.Unreadable) {
                syncConfirm = Confirm(
                    title = s.settingsResumeSyncReplaceTitle,
                    message = s.settingsResumeSyncReplaceMessage,
                    detail = null,
                    confirmLabel = s.settingsResumeSyncReplace,
                ) {
                    com.tmplayer.platform.Background.scope.launch {
                        com.tmplayer.data.ResumeSync.sync(settings, manual = true, replace = true)
                    }
                }
            } else {
                com.tmplayer.platform.Background.scope.launch { com.tmplayer.data.ResumeSync.sync(settings, manual = true) }
            }
        }
        Action(s.settingsResumeSyncDelete, s.settingsResumeSyncDeleteDetail, TmIcons.Delete, end = RowEnd.None) {
            syncConfirm = Confirm(
                title = s.settingsResumeSyncDeleteTitle,
                message = s.settingsResumeSyncDeleteMessage,
                detail = null,
                confirmLabel = s.commonDelete,
            ) {
                scope.launch {
                    val deleted = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { com.tmplayer.data.ResumeSync.turnOffAndDelete(settings) }.getOrDefault(false)
                    }
                    toast(if (deleted) s.settingsResumeSyncDeleted else s.settingsResumeSyncDeleteFailed, if (deleted) ToastKind.Success else ToastKind.Error)
                }
            }
        }
    }
    syncConfirm?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            detail = asked.detail,
            confirmLabel = asked.confirmLabel ?: s.commonClear,
            destructive = asked.destructive,
            onConfirm = {
                syncConfirm = null
                asked.action()
            },
            onDismiss = { syncConfirm = null },
        )
    }
    ProfileRows(state)
    // Last, under the card rather than in it: the one press here that ends the session. It asks first.
    SignOutButton(s.settingsSignOut) { confirmSignOut = true }
    if (confirmSignOut) SignOutDialog(state, onDismiss = { confirmSignOut = false })
}

/**
 * The profiles on this computer (HX88): Switch profile and Remove all accounts once there are two,
 * Add account while there is room (one profile too: it is how a second one is added). Each press
 * goes through the process's [com.tmplayer.ui.lock.ProfileFlow], which draws what it asks (the app
 * lock's PIN, the question while a video plays, the picker) over this page.
 */
@Composable
internal fun ProfileRows(state: ShellState) {
    val s = LocalStrings.current
    val flow = remember { com.tmplayer.desktop.profiles.DesktopProfiles.flow() }
    val list by remember(flow) {
        flow?.switcher?.profiles ?: kotlinx.coroutines.flow.MutableStateFlow(com.tmplayer.data.AccountRules.adopted(0L))
    }.collectAsState()
    val several = com.tmplayer.data.ProfilePicker.atLaunch(list)
    var confirmRemoveAll by remember { mutableStateOf(false) }
    if (several) {
        Action(s.profilesSwitchProfile, list.active?.shownName.orEmpty(), TmIcons.Person) { flow?.ask() }
    }
    if (com.tmplayer.data.AccountRules.canAdd(list)) {
        Action(s.profilesAdd, s.profilesAddDetail(count = com.tmplayer.data.AccountRules.MAX), TmIcons.PersonAdd) { flow?.add() }
    }
    if (several) {
        Action(s.profilesRemoveAll, s.profilesRemoveAllDetail, TmIcons.Delete) { confirmRemoveAll = true }
    }
    if (confirmRemoveAll) {
        ConfirmDialog(
            title = s.profilesRemoveAllTitle,
            message = s.profilesRemoveAllBody,
            confirmLabel = s.profilesRemoveAll,
            destructive = true,
            onDismiss = { confirmRemoveAll = false },
            onConfirm = {
                confirmRemoveAll = false
                flow?.removeAll(deleteDownloads = false) { result ->
                    if (result is com.tmplayer.data.SignOutResult.SignedOut) {
                        java.awt.EventQueue.invokeLater {
                            state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                        }
                    }
                }
            },
        )
    }
}

/**
 * Downloads and data, under the storage rows (WP-08): the backup, to a file and back. Settings,
 * lists and resume points, never the Telegram sign in.
 */
@Composable
internal fun BackupRows(state: ShellState, version: String) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    // A backup file picked, with what restoring it would change, while the prompt is up.
    var restorePlan by remember { mutableStateOf<RestorePlan?>(null) }
    val backups = remember(settings, state.watched, version) {
        BackupService(settings, state.watched, OnlineMetadata.current?.store, version, bookmarks = state.bookmarks)
    }
    Group(s.backupSection)
    Action(s.backupBackUp, s.backupBackUpDetail, TmIcons.Download) {
        scope.launch {
            val file = FilePicker.save(s.backupSaveTitle, backups.fileName(), "json") ?: return@launch
            val saved = withContext(Dispatchers.IO) { runCatching { file.writeText(backups.export()) }.isSuccess }
            toast(if (saved) s.backupSavedTo(file.absolutePath) else s.backupFailed, if (saved) ToastKind.Success else ToastKind.Error)
        }
    }
    // A restore under way (WP-97): the row says so and does nothing when pressed again.
    val restoring by com.tmplayer.data.RestoreRun.running.collectAsState()
    Action(s.backupRestore, if (restoring) s.backupRestoring else s.backupRestoreDetail, TmIcons.Refresh, enabled = !restoring) {
        scope.launch {
            val file = FilePicker.open(s.backupPickTitle, "json") ?: return@launch
            val text = withContext(Dispatchers.IO) {
                // No backup comes near this; a video picked by mistake is not read whole.
                runCatching { file.takeIf { it.length() <= 32L * 1024 * 1024 }?.readText() }.getOrNull()
            }
            if (text == null) {
                toast(s.backupReadFailed, ToastKind.Error)
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

    restorePlan?.let { plan ->
        ConfirmDialog(
            title = s.backupRestoreTitle,
            message = BackupWords.restoreMessage(plan),
            detail = s.backupRestoreUntouched,
            confirmLabel = s.backupRestoreConfirm,
            destructive = false,
            onConfirm = {
                restorePlan = null
                // Once at a time, off the window's thread, and the truth when it fails (RestoreRun); for
                // the profile open now, so a switch from the profile menu part way stops it (SEC2-04).
                com.tmplayer.data.RestoreRun.start(
                    work = { withContext(Dispatchers.IO) { backups.restore(plan.backup) } },
                ) { outcome ->
                    when (outcome) {
                        com.tmplayer.data.RestoreRun.Outcome.Done -> toast(s.backupRestored, ToastKind.Success)
                        com.tmplayer.data.RestoreRun.Outcome.Failed -> toast(s.backupRestoreFailed, ToastKind.Error)
                        com.tmplayer.data.RestoreRun.Outcome.AlreadyRunning -> Unit
                    }
                }
            },
            onDismiss = { restorePlan = null },
        )
    }
}

/**
 * Help (WP-08): the tour, what is new, the ways to reach the developer, the keyboard sheet, and the
 * two pages under it, Redo setup choices and Troubleshooting.
 */
@Composable
internal fun HelpRows(state: ShellState, onOpenPage: (SettingsCategory) -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<com.tmplayer.data.FeedbackReport.Kind?>(null) }
    Action(s.settingsTour, s.settingsTourBody, TmIcons.Help) { scope.launch { settings.openTour() } }
    Action(s.settingsWhatsNew, s.settingsWhatsNewDetail(WhatsNew.VERSION), TmIcons.StarOutline) { whatsNew = true }
    Action(s.settingsChangelog, s.settingsChangelogDetail, TmIcons.List, end = RowEnd.External) {
        OpenExternal.browse(WhatsNew.CHANGELOG)
    }
    val inbox = com.tmplayer.data.FeedbackReports.available
    Action(s.settingsReportProblem, if (inbox) s.settingsReportProblemDetailInbox else s.settingsReportProblemDetail, TmIcons.Bug) {
        reporting = com.tmplayer.data.FeedbackReport.Kind.Problem
    }
    // HX64: an idea, written here with the inbox, else on GitHub's feature request page.
    Action(s.settingsRequestFeature, s.settingsRequestFeatureDetail, TmIcons.Send) {
        reporting = com.tmplayer.data.FeedbackReport.Kind.Feature
    }
    // The shell's keys, as the sheet "?" and F1 open (WP-51).
    Action(s.settingsKeyboardShortcuts, s.keysShellTitle, TmIcons.List) { state.shortcutsOpen = true }
    Action(s.settingsRedoSetupChoices, s.setupChoicesSummary, TmIcons.Checklist) { onOpenPage(SettingsCategory.SetupChoices) }
    Action(s.settingsPageTroubleshooting, s.settingsPageTroubleshootingSummary, TmIcons.Logs) { onOpenPage(SettingsCategory.Troubleshooting) }

    if (whatsNew) WhatsNewPopup(onClose = { whatsNew = false })
    reporting?.let { kind -> FeedbackPopup(onClose = { reporting = null }, kind = kind) }
}

/**
 * Troubleshooting, under Help (WP-08): the proxy for a network where Telegram is blocked, the logs
 * to attach to a report, and for developers the name report.
 */
@Composable
internal fun TroubleshootingRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val developerOptions by settings.developerOptions.collectAsState(initial = false)
    // A proxy (CP77), for a network where Telegram is blocked: the connecting help's own screen.
    val proxyOn by com.tmplayer.data.Td.proxyOn.collectAsState()
    Action(s.proxyTitle, if (proxyOn) s.proxyInUse else s.proxySettingsDetailOff, TmIcons.Wifi) {
        com.tmplayer.ui.connect.ProxyScreenState.open = true
    }
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
            toast(if (saved) s.logsSavedTo(file.absolutePath) else s.logsFailed, if (saved) ToastKind.Success else ToastKind.Error)
        }
    }
    // Developers only (see DevOptions): how the last chat's file names were read.
    if (developerOptions || DevOptions.forced) {
        Action(s.logsNameReport, s.logsNameReportDetail, TmIcons.Checklist, end = RowEnd.None) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { saveNameCheck() } }
                file.onSuccess { toast(if (it == null) s.logsNameReportEmpty else s.logsNameReportSaved(it.absolutePath)) }
                    .onFailure { toast(s.logsFailed, ToastKind.Error) }
            }
        }
    }
}

/**
 * About (WP-08): the version and its updates (only where this build updates itself), the licence
 * and notices, Support and About TMPlayer, and the privacy note at the end.
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
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    var supporting by remember { mutableStateOf(false) }

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
                    else -> toast(s.settingsUpToDate(version), ToastKind.Success)
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

    if (supporting) SupportPopup(onClose = { supporting = false }, onStarred = { Background.scope.launch { runCatching { settings.markSupportStarred() } } })
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
