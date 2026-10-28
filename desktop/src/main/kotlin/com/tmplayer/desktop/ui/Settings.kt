package com.tmplayer.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.release
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.player.SubtitlePosition
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.SettingsDividerStart
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.lock.AppLockWords
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pages Settings is split into, the phone's own (SettingsScreen's `SettingsPage`) in the
 * phone's order. [Mouse] stands where the phone has Player gestures: how the pointer drives the
 * player, which is what the phone's gestures are for a finger.
 */
enum class SettingsCategory {
    SetupChoices, Appearance, Playback, Subtitles, Mouse, Library, Home, Notifications, Storage, Lock, About;

    internal val icon: androidx.compose.ui.graphics.vector.ImageVector
        get() = when (this) {
            SetupChoices -> TmIcons.Checklist
            Appearance -> TmIcons.Palette
            Playback -> TmIcons.Play
            Subtitles -> TmIcons.Subtitles
            Mouse -> SettingsGlyphs.Mouse
            Library -> TmIcons.Grid
            Home -> TmIcons.Home
            Notifications -> TmIcons.Bell
            Storage -> TmIcons.Download
            Lock -> TmIcons.Lock
            About -> TmIcons.Info
        }

    internal companion object {
        /** The page a setup step's settings also live on, for Setup choices' "Also in". */
        fun holding(step: SetupStep): SettingsCategory? = when (step) {
            SetupStep.Posters -> Library
            SetupStep.Storage -> Storage
            SetupStep.Performance, SetupStep.MobileData, SetupStep.Device, SetupStep.CrashReports -> null
        }
    }
}

/**
 * The pages this build's Settings lists, in order: every one on the desktop. The browser build
 * sets its own shorter list at start (com.tmplayer.web.Main), for the pages it has.
 */
var settingsCategories: List<SettingsCategory> = SettingsCategory.entries

/**
 * A page's line in the list in place of the desktop's, for a build whose page holds less: the
 * browser's Notifications has no downloads, storage or updates to speak of. Null keeps the line.
 */
var settingsSummaryOverride: (SettingsCategory) -> String? = { null }

/** A page's name, as its row in the list and its heading say it. */
@Composable
internal fun titleOf(category: SettingsCategory): String {
    val s = LocalStrings.current
    return when (category) {
        SettingsCategory.SetupChoices -> s.setupChoicesTitle
        SettingsCategory.Appearance -> s.settingsPageAppearance
        SettingsCategory.Playback -> s.settingsPlayback
        SettingsCategory.Subtitles -> s.settingsSubtitles
        SettingsCategory.Mouse -> s.settingsPageMouse
        SettingsCategory.Library -> s.settingsLibrary
        SettingsCategory.Home -> s.settingsPageHome
        SettingsCategory.Notifications -> s.settingsPageNotifications
        SettingsCategory.Storage -> s.settingsPageStorage
        SettingsCategory.Lock -> s.lockTitle
        SettingsCategory.About -> s.settingsPageAbout
    }
}

/**
 * Settings on the desktop, organised as the phone's: a list of pages (who is signed in over it,
 * Sign out under it), each page its grouped cards of rows. A window wide enough for both keeps the
 * list on the left and the open page beside it, as a desktop's preferences window does; a narrow
 * one shows the list, and a page in its place with a Back arrow, as the phone does. Esc goes back
 * there, the arrow keys walk the rows, and Tab reaches every control.
 *
 * [initialCategory] is the page to open on (the wide layout opens on Appearance without one).
 */
@Composable
fun SettingsPage(
    state: ShellState,
    version: String = "",
    /** The chat list as loaded, for the kids lock's picker. */
    chatList: List<com.tmplayer.data.ChatSummary> = emptyList(),
    /** The signed in account, shown over the list; null leaves the header out. */
    account: com.tmplayer.data.Account? = null,
    initialCategory: SettingsCategory? = null,
    /** Told once [initialCategory] has been opened, so a later visit opens on the list again. */
    onInitialTaken: () -> Unit = {},
) {
    var showAbout by remember { mutableStateOf(false) }
    val settings = state.settings
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    if (showAbout) {
        AboutPage(version, supporter = supportCounters.supporter, onBack = { showAbout = false })
        return
    }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    // The app lock (CP57): a PIN being set, changed or removed takes the page's place.
    val appLock by settings.appLock.collectAsState(initial = AppLockSettings())
    var pinFlow by remember { mutableStateOf<DesktopPinFlow?>(null) }
    var pickingKidsChats by remember { mutableStateOf(false) }
    // Neither the pad nor the Devices page has a page header: the buttons show on their own.
    pinFlow?.let { flow ->
        CoverWindowControls()
        PinFlowPad(flow, state, appLock, scope, toast, onNext = { pinFlow = it }, onKidsChats = { pickingKidsChats = true })
        return
    }
    // The Devices page (CP83), in place of Settings while it is open, so it is behind the same PIN
    // and goes with Settings when the window moves elsewhere.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.tmplayer.ui.settings.DevicesScreenState.open = false } }
    if (com.tmplayer.ui.settings.DevicesScreenState.open) {
        CoverWindowControls()
        com.tmplayer.ui.settings.DevicesScreen(onClose = { com.tmplayer.ui.settings.DevicesScreenState.open = false })
        return
    }

    // The page open, by name so it survives a trip to another destination and back.
    var openName by rememberSaveable { mutableStateOf(initialCategory?.name) }
    // Asked for while Settings was already up (or kept): opened all the same.
    LaunchedEffect(initialCategory) {
        val asked = initialCategory ?: return@LaunchedEffect
        openName = asked.name
        onInitialTaken()
    }
    val open = openName?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
    // Setup choices' "Also in": the row to bring the viewer to once its page is open, and the step
    // to come back to, since Back from that page returns to Setup choices.
    var jumpTo by remember { mutableStateOf<SetupStep?>(null) }
    var cameFromStep by remember { mutableStateOf<SetupStep?>(null) }
    val jumps = remember { SetupStep.entries.associateWith { JumpTarget() } }
    val keyboard = remember { KeyboardFocus() }
    val categoryFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<SettingsCategory?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // What Esc does: back to the list from a page in the narrow layout, nothing otherwise.
    val escape = remember { mutableStateOf<(() -> Unit)?>(null) }

    val pages = SettingsPages(
        state = state,
        version = version,
        chatList = chatList,
        jumps = jumps,
        cameFromStep = cameFromStep,
        onJump = { step ->
            SettingsCategory.holding(step)?.let { target ->
                cameFromStep = step
                jumpTo = step
                openName = target.name
            }
        },
        onPin = { pinFlow = it },
        onKidsChats = { pickingKidsChats = true },
        onShowAbout = { showAbout = true },
    )

    LaunchedEffect(open, jumpTo) {
        val step = jumpTo ?: return@LaunchedEffect
        if (open == null || open == SettingsCategory.SetupChoices) return@LaunchedEffect
        jumps.getValue(step).arrive()
        jumpTo = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .tracksKeyboardFocus(keyboard)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> escape.value?.let { back ->
                        back()
                        true
                    } ?: false
                    // Up and Down walk the rows, as they walk a list anywhere else in the app; Left
                    // and Right are left to the segments and to crossing between the two panes.
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    else -> false
                }
            },
    ) {
        val wide = maxWidth >= TWO_PANE_FROM
        val closePage: () -> Unit = {
            val back = cameFromStep
            if (back != null && open != SettingsCategory.SetupChoices) {
                openName = SettingsCategory.SetupChoices.name
            } else {
                cameFromStep = null
                returnTo = open
                openName = null
            }
        }
        // Keyboard focus follows the page in the narrow layout, where the list and the page take
        // turns: onto the page's Back on the way in, back to the row that opened it on the way out.
        LaunchedEffect(open, wide) {
            if (wide || !keyboard.keyboard || jumpTo != null) return@LaunchedEffect
            withFrameNanos { }
            if (open != null) {
                runCatching { backFocus.requestFocus() }
            } else {
                returnTo?.let { runCatching { categoryFocus.getValue(it).requestFocus() } }
            }
        }
        CompositionLocalProvider(LocalKeyboardFocus provides keyboard) {
            val list: @Composable () -> Unit = {
                CategoryList(
                    state = state,
                    version = version,
                    account = account,
                    selected = if (wide) open ?: settingsCategories.first() else null,
                    focus = categoryFocus,
                    onOpen = { category ->
                        cameFromStep = null
                        jumpTo = null
                        openName = category.name
                    },
                    onSignOut = { confirmSignOut = true },
                )
            }
            if (wide) {
                val shown = open ?: settingsCategories.first()
                Column(Modifier.fillMaxSize()) {
                    PageHeader(s().settingsTitle, s().settingsSavesAsYouGo)
                    Row(
                        Modifier.fillMaxSize().padding(start = pageMargin()),
                        horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
                    ) {
                        ScrollPane(Modifier.width(LIST_WIDTH).fillMaxHeight()) { list() }
                        key(shown) {
                            ScrollPane(Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.widthIn(max = PAGE_MAX)) {
                                    Text(
                                        titleOf(shown),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Tone.text,
                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                                    )
                                    pages.Content(shown)
                                }
                            }
                        }
                    }
                }
            } else {
                key(open) {
                    ScrollPane(Modifier.fillMaxSize(), inset = false) {
                        if (open == null) {
                            PageHeader(s().settingsTitle, s().settingsSavesAsYouGo)
                            Column(Modifier.widthIn(max = PAGE_MAX).padding(horizontal = pageMargin())) { list() }
                        } else {
                            PageHeader(
                                title = titleOf(open),
                                leading = {
                                    val interactions = remember { MutableInteractionSource() }
                                    BarTooltip(s().aboutBackToSettings, "Esc", below = true) {
                                        IconButton(
                                            onClick = closePage,
                                            interactionSource = interactions,
                                            modifier = Modifier.focusRequester(backFocus).keyboardRing(interactions, CircleShape),
                                        ) { Icon(TmIcons.ArrowBack, contentDescription = s().aboutBackToSettings) }
                                    }
                                },
                            )
                            Column(Modifier.widthIn(max = PAGE_MAX).padding(horizontal = pageMargin())) { pages.Content(open) }
                        }
                    }
                }
            }
        }
        // Esc on a page goes back to the list, as the phone's Back does; the wide layout has the
        // list beside the page already, so there it is left alone.
        androidx.compose.runtime.SideEffect { escape.value = if (!wide && open != null) closePage else null }
    }

    if (confirmSignOut) SignOutDialog(state, onDismiss = { confirmSignOut = false })
    if (pickingKidsChats) {
        KidsChatsDialog(chatList, appLock.kidsChats, settings, onClose = { pickingKidsChats = false })
    }
}

/** The strings, for the places a lambda cannot read [LocalStrings] through a local. */
@Composable
private fun s() = LocalStrings.current

/**
 * One scrolling column with its scroll bar, the content held clear of the bar and of the floating
 * bar at the foot of the window. [inset] keeps a gutter for the bar on a pane's right.
 */
@Composable
private fun ScrollPane(modifier: Modifier, inset: Boolean = true, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(end = if (inset) 16.dp else 0.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp + LocalBottomChrome.current))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * The list of pages: who is signed in, "Recommended for this device" while it has something to
 * say, one row per page with its most telling value under its name, and Sign out at the end, in
 * the phone's order. [selected] is the page open beside it in the wide layout, null in the narrow
 * one, where each row leads on to its page.
 */
@Composable
private fun CategoryList(
    state: ShellState,
    version: String,
    account: com.tmplayer.data.Account?,
    selected: SettingsCategory?,
    focus: Map<SettingsCategory, FocusRequester>,
    onOpen: (SettingsCategory) -> Unit,
    onSignOut: () -> Unit,
) {
    val s = LocalStrings.current
    val settings = state.settings
    val toast = rememberToast()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val language by settings.language.collectAsState(initial = "")
    val subtitleStyle by settings.subtitleStyle.collectAsState(initial = SubtitleStyle())
    val appLock by settings.appLock.collectAsState(initial = AppLockSettings())
    val updateState by Updates.state.collectAsState()
    // The free space where downloads go, once measured, which is what somebody opening Storage
    // is usually after; reading it is a blocking call, so it is not made while drawing.
    val disk by produceState<DiskInfo?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { DiskInfo.of(DesktopPaths.downloadsDir) }.getOrNull() }
    }
    val offered = if (state.extras.updates != null) updateState.release else null

    if (account != null) AccountHeader(account)
    com.tmplayer.ui.onboarding.DeviceFitSettingsCard(
        settings,
        onApplied = { toast(s.deviceApplied) },
        modifier = Modifier.padding(vertical = 8.dp),
    )
    SettingsColumn(Modifier.padding(top = 4.dp), dividerStart = SettingsDividerStart) {
        settingsCategories.forEach { category ->
            val summary = when (category) {
                SettingsCategory.SetupChoices -> s.setupChoicesSummary
                SettingsCategory.Appearance -> s.settingsPageAppearanceSummary(theme = theme.label, language = s.languageRowDetail(language))
                SettingsCategory.Playback -> s.settingsPagePlaybackSummaryDesktop
                SettingsCategory.Subtitles -> {
                    val style = listOf(
                        subtitleStyle.size.label,
                        if (subtitleStyle.box) s.settingsSubtitleStyleBox else s.settingsSubtitleStyleNoBox,
                        subtitleStyle.position.label,
                    ).joinToString("  ·  ")
                    if (OnlineSubtitles.available) s.settingsPageSubtitlesSummaryOnline(style) else style
                }
                SettingsCategory.Mouse -> s.settingsPageMouseSummary
                SettingsCategory.Library -> s.settingsPageLibrarySummary
                SettingsCategory.Home -> s.settingsPageHomeSummary
                SettingsCategory.Notifications -> settingsSummaryOverride(category) ?: s.settingsPageNotificationsSummary
                SettingsCategory.Storage -> disk?.takeIf { it.totalBytes > 0 }?.let {
                    s.settingsStorageFree(StorageRelocationPlan.size(it.freeBytes), StorageRelocationPlan.size(it.totalBytes))
                } ?: s.settingsPageStorageSummary
                SettingsCategory.Lock -> AppLockWords.summary(appLock)
                // An update waiting is said here as well as inside, in the same colour, so it is
                // not hidden one level down.
                SettingsCategory.About -> offered?.let { UpdateWords.settingsRow(it) } ?: s.settingsPageAboutSummary(version)
            }
            val caution = category == SettingsCategory.About && offered != null
            Setting(
                titleOf(category),
                summary,
                modifier = Modifier.focusRequester(focus.getValue(category)),
                icon = category.icon,
                iconTint = if (caution) Tone.caution else Tone.accent,
                onClick = { onOpen(category) },
                selected = category == selected,
            ) { if (selected == null) RowTail(null, RowEnd.Chevron) }
        }
    }
    // Last, under the card rather than in it: the one press here that ends the session.
    SignOutButton(s.settingsSignOut, onSignOut)
}

/**
 * Every page's rows, with what the pages share: the shell, the jump targets Setup choices
 * sends the viewer to, and the ways out to the PIN pad, the kids lock's picker and About.
 */
internal class SettingsPages(
    val state: ShellState,
    val version: String,
    val chatList: List<com.tmplayer.data.ChatSummary>,
    val jumps: Map<SetupStep, JumpTarget>,
    val cameFromStep: SetupStep?,
    val onJump: (SetupStep) -> Unit,
    val onPin: (DesktopPinFlow) -> Unit,
    val onKidsChats: () -> Unit,
    val onShowAbout: () -> Unit,
) {
    @Composable
    fun Content(category: SettingsCategory) {
        when (category) {
            SettingsCategory.SetupChoices -> SetupChoicesContent(
                state,
                focusStep = cameFromStep,
                onJump = onJump,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> SettingsColumn(Modifier.padding(vertical = 4.dp), dividerStart = SettingsDividerStart) {
                when (category) {
                    SettingsCategory.Appearance -> AppearanceRows(state)
                    SettingsCategory.Playback -> PlaybackRows(state, jumps)
                    SettingsCategory.Subtitles -> SubtitlesRows(state)
                    SettingsCategory.Mouse -> MouseRows(state)
                    SettingsCategory.Library -> LibraryRows(state, version, chatList, jumps)
                    SettingsCategory.Home -> HomeLayoutRows(state, chatList)
                    SettingsCategory.Notifications -> NotificationRows(state)
                    SettingsCategory.Storage -> StorageGroup(state, jumps)
                    SettingsCategory.Lock -> LockRows(state, onPin, onKidsChats)
                    SettingsCategory.About -> AboutRows(state, version, onShowAbout)
                    SettingsCategory.SetupChoices -> Unit
                }
            }
        }
    }
}

/** The narrowest window that still has room for the list and a page side by side. */
private val TWO_PANE_FROM = 960.dp

/** The list's width beside a page. */
private val LIST_WIDTH = 340.dp

/** Between the list and the page. */
private val PANE_GAP = 24.dp

/** A page's rows stop growing here, as a line of text does. */
private val PAGE_MAX = 760.dp

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
            color = Color(style.colour.argb),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            fontFamily = when (style.face) {
                com.tmplayer.player.SubtitleFace.Default -> null
                com.tmplayer.player.SubtitleFace.Serif -> androidx.compose.ui.text.font.FontFamily.Serif
                com.tmplayer.player.SubtitleFace.Monospace -> androidx.compose.ui.text.font.FontFamily.Monospace
            },
            fontWeight = if (style.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            // mpv's outline or shadow, near enough; the box replaces either, as mpv's does.
            shadow = when {
                style.box -> null
                style.edge == com.tmplayer.player.SubtitleEdge.Outline -> Shadow(Color.Black, blurRadius = 3f)
                style.edge == com.tmplayer.player.SubtitleEdge.Shadow ->
                    Shadow(Color.Black.copy(alpha = 0.75f), offset = androidx.compose.ui.geometry.Offset(2f, 2f), blurRadius = 2f)
                else -> null
            },
        )
        Text(
            s.subtitlesPreview,
            style = text,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = foot)
                .then(if (style.box) Modifier.background(Color(style.boxLevel.argb)).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier),
        )
    }
}

/** A PIN being set, changed or removed from the App lock page. See the phone's `PinFlow`. */
internal enum class DesktopPinFlow { Set, SetForKids, ChangeCheck, ChangeChoose, Remove }

/**
 * The pad for [flow], in the page's place: Cancel and Esc go back to the page.
 *
 * [scope] and [toast] are the page's: a finished PIN takes the pad away at once, and work launched
 * on the pad's own scope was cancelled with it, halfway through saving the PIN.
 */
@Composable
private fun PinFlowPad(
    flow: DesktopPinFlow,
    state: ShellState,
    appLock: AppLockSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    toast: (String) -> Unit,
    onNext: (DesktopPinFlow?) -> Unit,
    onKidsChats: () -> Unit,
) {
    val s = LocalStrings.current
    val settings = state.settings
    val checking = flow == DesktopPinFlow.ChangeCheck || flow == DesktopPinFlow.Remove
    PinPad(
        settings = settings,
        purpose = if (checking) PinPurpose.Check else PinPurpose.Choose,
        title = if (checking) s.lockEnterCurrent else s.lockNewPin,
        onDone = { pin ->
            when (flow) {
                DesktopPinFlow.ChangeCheck -> onNext(DesktopPinFlow.ChangeChoose)
                DesktopPinFlow.Remove -> {
                    onNext(null)
                    scope.launch {
                        settings.removePin()
                        toast(s.lockPinRemovedToast)
                    }
                }
                DesktopPinFlow.Set, DesktopPinFlow.ChangeChoose, DesktopPinFlow.SetForKids -> {
                    onNext(null)
                    scope.launch {
                        settings.setPin(pin)
                        if (flow == DesktopPinFlow.SetForKids) {
                            settings.setKidsLock(true)
                            if (appLock.kidsChats.isEmpty()) onKidsChats()
                        }
                        toast(s.lockPinSetToast)
                    }
                }
            }
        },
        onCancel = { onNext(null) },
        showForgot = false,
    )
}

/**
 * The kids lock's chats (CP57): every chat in the account with a box each, saved as it is ticked.
 * The live list is what the lock already filters, so the last sync's whole list is read beside it.
 */
@Composable
private fun KidsChatsDialog(
    chatList: List<com.tmplayer.data.ChatSummary>,
    chosen: Set<Long>,
    settings: SettingsStore,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val every by produceState(initialValue = chatList, chatList) {
        val remembered = runCatching { settings.cachedChatSnapshot() }.getOrNull().orEmpty()
        value = (chatList + remembered).distinctBy { it.id }
    }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.lockAllowedChats) },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(s.lockAllowedChatsNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                val scroll = rememberScrollState()
                Column(Modifier.padding(top = 12.dp).heightIn(max = 420.dp).verticalScroll(scroll)) {
                    every.forEach { chat ->
                        val ticked = chat.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch { settings.setKidsChats(if (ticked) chosen - chat.id else chosen + chat.id) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = ticked, onCheckedChange = null)
                            Text(chat.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/**
 * The signed in account over the list, as the phone's Settings opens: the picture, with the name
 * and @username beside it, on the page's own ground rather than in a card.
 */
@Composable
private fun AccountHeader(account: com.tmplayer.data.Account) {
    Row(
        Modifier.fillMaxWidth().padding(start = SETTING_INSET, end = SETTING_INSET, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChatAvatar(account.miniThumbnail, account.photoFileId, account.name, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.name, style = MaterialTheme.typography.titleMedium, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (account.username.isNotBlank()) {
                // Isolated left to right, or an Arabic or Persian interface draws "name@".
                Text("⁦@${account.username}⁩", style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1)
            }
        }
    }
}

/** Sign out at the end of the list: full width, in the danger colour. It still asks first. */
@Composable
private fun SignOutButton(label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    androidx.compose.material3.Button(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp).keyboardRing(interactions, CircleShape),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Tone.danger,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(TmIcons.SignOut, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Settings, Subtitle style, on the desktop: the preview at the top, where each change shows the
 * moment it is made, and under it the size, colour, background box and how dark, edge, typeface,
 * weight and position, then Back to the default look. Each is saved as it changes; Close leaves.
 */
@Composable
internal fun SubtitleStyleDialog(settings: SettingsStore, subtitleStyle: SubtitleStyle, onClose: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.settingsSubtitleStyle) },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                SubtitlePreview(subtitleStyle, Modifier.padding(bottom = 8.dp))
                SubtitleLookChoices(settings, subtitleStyle, scope)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
        dismissButton = {
            TextButton(
                onClick = { scope.launch { settings.setSubtitleStyle(SubtitleStyle()) } },
                enabled = !subtitleStyle.isDefault,
            ) { Text(s.subtitlesDefaultLook) }
        },
    )
}

/**
 * The look's choices under the preview, scrolling on their own so the preview stays in sight.
 * The edge is left out while there is a box: the box is the edge then, as in the player.
 */
@Composable
private fun SubtitleLookChoices(settings: SettingsStore, subtitleStyle: SubtitleStyle, scope: kotlinx.coroutines.CoroutineScope) {
    val s = LocalStrings.current
    fun look(next: SubtitleStyle) = scope.launch { settings.setSubtitleStyle(next) }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Setting(s.subtitlesSize, s.settingsSubtitleSizeHint(subtitleStyle.size.label)) {
            val sizes = SubtitleSize.entries
            fun size(by: Int) {
                val next = sizes[(subtitleStyle.size.ordinal + by).coerceIn(0, sizes.lastIndex)]
                scope.launch { settings.setSubtitleStyle(subtitleStyle.copy(size = next)) }
            }
            Stepper(
                onLess = { size(-1) },
                onMore = { size(1) },
                canLess = subtitleStyle.size.ordinal > 0,
                canMore = subtitleStyle.size.ordinal < sizes.lastIndex,
            )
        }
        Setting(s.subtitlesColour, subtitleStyle.colour.label) {
            Segments(com.tmplayer.player.SubtitleColour.entries, subtitleStyle.colour, { it.label }) { look(subtitleStyle.copy(colour = it)) }
        }
        Setting(s.subtitlesBoxDarkness, s.subtitlesBoxDarknessHint) {
            Segments(com.tmplayer.player.SubtitleBox.entries, subtitleStyle.boxLevel, { it.label }) { look(subtitleStyle.copy(boxLevel = it)) }
        }
        if (!subtitleStyle.box) {
            Setting(s.subtitlesEdge, subtitleStyle.edge.label) {
                Segments(com.tmplayer.player.SubtitleEdge.entries, subtitleStyle.edge, { it.label }) { look(subtitleStyle.copy(edge = it)) }
            }
        }
        Setting(s.subtitlesFace, subtitleStyle.face.label) {
            Segments(com.tmplayer.player.SubtitleFace.entries, subtitleStyle.face, { it.label }) { look(subtitleStyle.copy(face = it)) }
        }
        Toggle(s.subtitlesWeightBold, subtitleStyle.weightLabel, subtitleStyle.bold) { on -> look(subtitleStyle.copy(bold = on)) }
        Setting(s.subtitlesPosition, s.settingsSubtitlePositionHint) {
            Segments(SubtitlePosition.entries, subtitleStyle.position, { it.label }) { place -> look(subtitleStyle.copy(position = place)) }
        }
    }
}
