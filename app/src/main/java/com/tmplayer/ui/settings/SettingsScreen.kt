package com.tmplayer.ui.settings

import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.keepTopInSight
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
import androidx.compose.foundation.border
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.tmplayer.ui.components.FloatingBackButton
import com.tmplayer.ui.components.FloatingPageTitle
import com.tmplayer.ui.components.LocalBottomChrome
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.produceState
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
import com.tmplayer.data.BackupWords
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.hasPictureInPicture
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.lock.AppLockWords
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CrashReports
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MeteredPolicy
import com.tmplayer.data.Notice
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.RestorePlan
import com.tmplayer.data.ResumeRewind
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.WatchCache
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.data.TvChannelsPublisher
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.canInstall
import com.tmplayer.data.release
import com.tmplayer.data.updateScheduler
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.player.AudioDownmix
import com.tmplayer.player.SubtitlePosition
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.SettingIconChip
import com.tmplayer.ui.components.SettingsSectionLabel
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.components.jumpTarget
import com.tmplayer.ui.onboarding.SetupChoicesList
import com.tmplayer.ui.onboarding.SetupPlace
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.rememberUiScale
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
 * cached videos. [Gestures] is a phone's alone; a television never shows its row. [TvHome] is the
 * other way round, shown only where the Android TV home screen has Play next or channels. [SetupChoices]
 * gathers the setup's answers (SetupChoicesList), each with a way to the page it also lives on.
 */
enum class SettingsPage { SetupChoices, Appearance, Playback, Subtitles, Gestures, Library, Home, TvHome, Notifications, Storage, Lock, About }

/**
 * Focus for a row in a lazy list, which exists only once the list has been measured: a frame
 * first, and a couple more if the row is still not there to take it.
 */
private suspend fun FocusRequester.settle(): Boolean {
    repeat(3) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

private sealed interface Prompt {
    /**
     * Remove after watching being switched on, from Storage: from then on a download goes without
     * a press once it is watched, so it is asked first. Off deletes nothing and is not asked.
     */
    data object RemoveAfterWatching : Prompt

    /** "Get test versions" being switched on: pre-releases may have bugs, so it is asked first. Off is not asked. */
    data object TestVersions : Prompt
    data object ClearHistory : Prompt
    data object ClearWatched : Prompt
    data object ClearFavorites : Prompt

    /** The chats hidden from their menus, each with Show, and Show all. */
    data object HiddenChats : Prompt
    data object SignOut : Prompt

    /** A backup file picked, with what restoring it would change. */
    data class Restore(val plan: RestorePlan) : Prompt
}

/**
 * A PIN being set, changed or removed from App lock (CP57), drawn as the full-screen pad in place
 * of the page. Changing and removing ask for the current PIN first.
 */
private enum class PinFlow {
    /** A first PIN. */
    Set,

    /** A first PIN asked for by "Allowed chats only", which goes on once the PIN is set. */
    SetForKids,

    /** The current PIN, then [ChangeChoose]. */
    ChangeCheck,
    ChangeChoose,

    /** The current PIN, then the PIN goes. */
    Remove,
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
    /** Who is signed in, shown at the top of the list. Null while it is still being read. */
    account: com.tmplayer.data.Account? = null,
    onLoggedOut: () -> Unit,
    /** Leaving Settings. On a phone this is the app bar's arrow as well as the hardware key. */
    onBack: () -> Unit = {},
    /**
     * Storage's Free up space: the Downloads page with its review already up, the one place
     * anything TMPlayer holds is cleared.
     */
    onFreeUpSpace: () -> Unit = {},
    /** Settings, then About: the licence, the notices and every link. */
    onOpenAbout: () -> Unit = {},
    /**
     * The page to open on, or null for the category list. Also how a caller brings the viewer back
     * to the page they left for About or the cached videos, since this screen is gone meanwhile.
     */
    initialPage: SettingsPage? = null,
    /** Told every time a page opens or closes, so the caller can hand it back as [initialPage]. */
    onPageChange: (SettingsPage?) -> Unit = {},
    /** The account's Telegram folders; null reads them from TDLib. The promo fixture passes its own. */
    accountFolders: List<com.tmplayer.data.ChatFolderSummary>? = null,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    // Matches the stored default, so the switch does not show as on for the frame before the
    // first value arrives and then visibly flick off.
    val openLastChat by settings.openLastChat.collectAsStateWithLifecycle(initialValue = false)
    // Every row on until read, which is the stored default.
    val homeLayout by settings.homeLayout.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.HomeLayout())
    var pickingHomeSource by remember { mutableStateOf(false) }
    var pickingHomeChats by remember { mutableStateOf(false) }
    // The single video watching leaves behind. Empty until the first read, which reads the same as
    // having none: "Nothing cached" for a frame is honest either way.
    val cached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadFirst by settings.downloadBeforePlaying.collectAsStateWithLifecycle(initialValue = false)
    val lowStorage by settings.lowStorageMode.collectAsStateWithLifecycle(initialValue = false)
    val removeAfterWatching by settings.removeAfterWatching.collectAsStateWithLifecycle(initialValue = false)
    val autoplayNext by settings.autoplayNext.collectAsStateWithLifecycle(initialValue = true)
    val resumeRewindMs by settings.resumeRewindMs.collectAsStateWithLifecycle(initialValue = ResumeRewind.DEFAULT_MS)
    val detailFirst by settings.detailFirst.collectAsStateWithLifecycle(initialValue = true)
    val rememberSearches by settings.rememberSearches.collectAsStateWithLifecycle(initialValue = true)
    val resumeSync by settings.resumeSync.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by settings.wifiOnlyDownloads.collectAsStateWithLifecycle(initialValue = false)
    val saveMobileData by settings.saveMobileData.collectAsStateWithLifecycle(initialValue = false)
    val trickplayOn by settings.trickplay.collectAsStateWithLifecycle(initialValue = true)
    val watchNextOn by settings.watchNext.collectAsStateWithLifecycle(initialValue = false)
    val trickplayMemory = remember { DeviceQuirks.trickplayMemory(context) }
    val watchNextSupported = remember { DeviceQuirks.remote(context).watchNextSupported }
    val tvChannelsOn by settings.tvChannels.collectAsStateWithLifecycle(initialValue = true)
    val tvChannelsSupported = remember { TvChannelsPublisher.supported(context) }
    val touchPrefs by settings.touchPrefs.collectAsStateWithLifecycle(initialValue = TouchPrefs())
    val downmixChoice by settings.downmixChoice.collectAsStateWithLifecycle(initialValue = null)
    val evenVolume by settings.evenVolume.collectAsStateWithLifecycle(initialValue = true)
    val backgroundAudio by settings.backgroundAudio.collectAsStateWithLifecycle(initialValue = false)
    val pipOnLeave by settings.pictureInPictureOnLeave.collectAsStateWithLifecycle(initialValue = true)
    val pipAvailable = LocalContext.current.hasPictureInPicture()
    val subtitleStyle by settings.subtitleStyle.collectAsStateWithLifecycle(initialValue = SubtitleStyle())
    val trackPrefs by settings.trackPreferences.collectAsStateWithLifecycle(initialValue = TrackPreferences())
    val learntLanguages by settings.learntLanguages.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val videoDecoder by settings.videoDecoder.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.VideoDecoder.Automatic)
    val watchedStore = remember { WatchedStore(context) }
    val watchedList by watchedStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only those the kids lock shows, as the Favourites tab counts them.
    val storedFavorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    // The chats hidden from their menus (see HiddenChats), for Library's Hidden chats list.
    val hiddenChats by settings.hiddenChats.collectAsStateWithLifecycle(initialValue = emptyList())
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
    val favorites = remember(storedFavorites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavorites, allowedChats) }
    val liveFolders by Td.folders.collectAsStateWithLifecycle()
    val folders = accountFolders ?: liveFolders
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
    val pureBlack by settings.pureBlack.collectAsStateWithLifecycle(initialValue = false)
    val accent by settings.accent.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.Accent.Default)
    val customAccent by settings.customAccent.collectAsStateWithLifecycle(initialValue = null)
    val typeface by settings.typeface.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.AppTypeface.Default)
    val highContrast by settings.highContrast.collectAsStateWithLifecycle(initialValue = false)
    // What Back to the defaults took away, while its Undo is offered.
    var appearanceUndo by remember { mutableStateOf<com.tmplayer.data.AppearanceSnapshot?>(null) }
    LaunchedEffect(appearanceUndo) {
        if (appearanceUndo != null) {
            kotlinx.coroutines.delay(APPEARANCE_UNDO_MS)
            appearanceUndo = null
        }
    }
    val uiScale by rememberUiScale(settings)
    val lighterPictures by settings.lighterPictures.collectAsStateWithLifecycle(initialValue = false)
    val reduceMotion by settings.reduceMotion.collectAsStateWithLifecycle(initialValue = false)
    val crashReports by settings.crashReports.collectAsStateWithLifecycle(initialValue = false)
    // The app lock and the kids lock (CP57).
    val appLock by settings.appLock.collectAsStateWithLifecycle(initialValue = AppLockSettings())
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    var pickingKidsChats by remember { mutableStateOf(false) }

    val toast = rememberToast()
    val updateState by Updates.state.collectAsStateWithLifecycle()
    val updateNotify by settings.updateNotify.collectAsStateWithLifecycle(initialValue = true)
    val updateTestVersions by settings.updateTestVersions.collectAsStateWithLifecycle(initialValue = false)
    val updateAutoInstall by settings.updateAutoInstall.collectAsStateWithLifecycle(initialValue = false)
    var showUpdate by remember { mutableStateOf(false) }
    // A link a TV cannot open, shown as a QR code instead: the address and what it is.
    var qrLink by remember { mutableStateOf<Pair<String, String>?>(null) }
    var supporting by remember { mutableStateOf(false) }
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    // The language picker, the "What's new" sheet and "Report a problem", each over the list.
    var pickingLanguage by remember { mutableStateOf(false) }
    var stylingSubtitles by remember { mutableStateOf(false) }
    var showingLearnt by remember { mutableStateOf(false) }
    var trackPicker by remember { mutableStateOf<TrackLanguagePicker?>(null) }
    var pickingDecoder by remember { mutableStateOf(false) }
    var editingSizes by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var onlineDialog by remember { mutableStateOf<OnlineDialog?>(null) }
    var editingTmdbKey by remember { mutableStateOf(false) }
    var editingOmdbKey by remember { mutableStateOf(false) }
    val language by settings.language.collectAsStateWithLifecycle(initialValue = "")
    val openPage = { url: String, what: String -> if (!openLink(context, url)) qrLink = url to what }
    // What TMPlayer is holding, split into downloads, cache and everything else. Worked out by
    // [StorageSplit], which is also what the Downloads screen reads, so the two panels cannot
    // quote different numbers for the same disk.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    // Whether the count has come back at all: after clearing everything TMPlayer really holds 0
    // bytes, and the chart says so rather than waiting on a figure above zero (as Downloads does).
    var splitRead by remember { mutableStateOf(false) }
    // Filled by the first refresh, never read during composition: reading it is a blocking
    // statvfs().
    var disk by remember { mutableStateOf(DiskInfo.EMPTY) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val backup = rememberBackupActions(onPlan = { prompt = Prompt.Restore(it) }, say = { toast(it) })
    val saveLogs = rememberSaveLogs(say = { toast(it) })
    val saveNameCheck = rememberSaveNameCheck(say = { toast(it) })
    val developerOptions by settings.developerOptions.collectAsStateWithLifecycle(initialValue = false)
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
    // The Devices page (CP83), over the list while it is open.
    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    var devicesClosed by remember { mutableStateOf(false) }
    val devicesFocus = remember { FocusRequester() }
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
    // Setup choices' "Also in": the row to bring the viewer to once its page is open, and the
    // Setup choices row to come back to, since Back from that page returns to Setup choices.
    var jumpTo by remember { mutableStateOf<SetupStep?>(null) }
    var cameFromStep by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    val jumps = remember { SetupStep.entries.associateWith { JumpTarget() } }
    val closePage = {
        val back = cameFromStep
        if (back != null && page != SettingsPage.SetupChoices) {
            page = SettingsPage.SetupChoices
            onPageChange(SettingsPage.SetupChoices)
        } else {
            cameFromStep = null
            returnTo = page
            page = null
            onPageChange(null)
        }
    }
    fun titleOf(target: SettingsPage): String = when (target) {
        SettingsPage.SetupChoices -> s.setupChoicesTitle
        SettingsPage.Appearance -> s.settingsPageAppearance
        SettingsPage.Playback -> s.settingsPlayback
        SettingsPage.Subtitles -> s.settingsSubtitles
        SettingsPage.Gestures -> s.settingsPageGestures
        SettingsPage.Library -> s.settingsLibrary
        SettingsPage.Home -> s.settingsPageHome
        SettingsPage.TvHome -> s.settingsPageTvHome
        SettingsPage.Notifications -> s.settingsPageNotifications
        SettingsPage.Storage -> s.settingsPageStorage
        SettingsPage.Lock -> s.lockTitle
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
        splitRead = true
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
            // A jump from Setup choices lands on its row instead (the effect below).
            if (open != null && jumpTo != null && open != SettingsPage.SetupChoices) return@LaunchedEffect
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
                val row = categoryFocus.getValue(back ?: SettingsPage.SetupChoices)
                // A list drawn afresh (Settings left for Downloads and come back to) starts at
                // its top, where a row near the foot such as Storage is not laid out yet and
                // cannot take focus: the list goes to its foot for it, and focus brings it into view.
                if (!row.settle() && back != null && listState.layoutInfo.totalItemsCount > 0) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    if (!row.settle()) {
                        listState.scrollToItem(0)
                        row.settle()
                    }
                }
                if (back == null) {
                    withFrameNanos { }
                    listState.scrollToItem(0)
                }
            }
        }
    }

    // Setup choices' "Also in": once the page is open, its row is brought into view and lit, and on
    // a remote focused.
    LaunchedEffect(page, jumpTo) {
        val step = jumpTo ?: return@LaunchedEffect
        if (page == SettingsPage.SetupChoices) return@LaunchedEffect
        jumps.getValue(step).arrive(pageState, focus = !touch)
        jumpTo = null
    }

    // The PIN pad takes the whole screen, and the row that opened it is gone when it closes ("Set
    // a PIN" becomes "Change the PIN", and back), so the remote had nothing focused. It lands on the
    // page's first row again, once the pad has closed and again once the new PIN state is drawn.
    var pinPadWasOpen by remember { mutableStateOf(false) }
    if (!touch) {
        LaunchedEffect(pinFlow == null, appLock.pinSet) {
            if (pinFlow != null) {
                pinPadWasOpen = true
                return@LaunchedEffect
            }
            if (pinPadWasOpen && page == SettingsPage.Lock) landing.settle()
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
    // The category list keeps one state for as long as Settings is open, so coming back from
    // a page finds it scrolled where it was left. Each page starts at its own top.
    val shownState = if (page == null) listState else pageState
    LazyColumn(
        state = shownState,
        modifier = Modifier
            .fillMaxSize()
            // The heading at the top takes no focus: back on the first row, it comes back whole.
            .keepTopInSight(shownState, enabled = !touch)
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
            // A television's Settings opens from the account menu without the top bar over it.
            top = if (touch) 4.dp else Tv.SafeV,
            bottom = if (touch) maxOf(insets.calculateBottomPadding(), LocalBottomChrome.current) + 32.dp else 40.dp,
        ),
        // The gap between two rows of a phone's preference list is a divider's worth of nothing,
        // not a card margin.
        verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 10.dp),
    ) {
        // TV only: the phone's app bar already says where the viewer is and offers the way out.
        if (!touch) {
            // No top bar over Settings on a television: the list and each page say where the
            // remote is, in one compact line.
            (page?.let { titleOf(it) } ?: s.settingsTitle).let { heading ->
                item {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleLarge,
                        color = Tone.text,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
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

        // "Recommended for this device" (CP68), above the categories while anything recommended
        // for a device short of memory or storage is still off. It draws nothing otherwise.
        if (page == null) {
            item {
                com.tmplayer.ui.onboarding.DeviceFitSettingsCard(
                    settings,
                    onApplied = { toast(s.deviceApplied) },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        // Each page below holds what used to be one or more sections of a single long list. A
        // page that took in more than one keeps the old headings between them.
        // Each run of rows between two headings is drawn as one card on a phone (settingsGroups).
        settingsGroups { when (page) {
            // The setup's answers in the setup's order, each with the page it also lives on.
            SettingsPage.SetupChoices -> {
                settingsNote {
                    SetupChoicesList(
                        settings = settings,
                        place = { step ->
                            val target = when (step) {
                                SetupStep.Posters -> SettingsPage.Library
                                SetupStep.Storage -> SettingsPage.Storage
                                SetupStep.Performance -> SettingsPage.Appearance
                                SetupStep.MobileData -> SettingsPage.Storage
                                // Its settings are on three pages; each row above has its own.
                                SetupStep.Device -> null
                                // Never listed here: its switch is on the privacy rows already.
                                SetupStep.CrashReports -> null
                            }
                            target?.let {
                                SetupPlace(titleOf(it)) {
                                    cameFromStep = step
                                    jumpTo = step
                                    showPage(it)
                                }
                            }
                        },
                        onRunSetupAgain = { scope.launch { settings.replaySetup() } },
                        landing = landing,
                        focusStep = cameFromStep,
                        modifier = Modifier.padding(top = if (touch) 8.dp else 0.dp, bottom = 16.dp),
                    )
                }
            }

            SettingsPage.Appearance -> {
                // The choices below, live: a card drawn in the theme, colour, typeface and contrast as they stand.
                item(key = "appearance_preview") {
                    com.tmplayer.ui.components.AppearancePreview(
                        Modifier.padding(horizontal = if (touch) 16.dp else 0.dp, vertical = 8.dp),
                    )
                }
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

                // Pure black, the usual switch under the theme: wherever the app can be dark, so
                // System and Dark, and folded away under Light. Right under Theme on a TV too, one
                // press of Down away.
                item(key = "pure_black") {
                    com.tmplayer.ui.components.RevealUnder(themeChoice.offersPureBlack) {
                        ToggleRow(
                            title = s.settingsPureBlack,
                            subtitle = s.themeBlackDetail,
                            icon = TmIcons.PureBlack,
                            checked = pureBlack,
                            onToggle = { scope.launch { settings.setPureBlack(!pureBlack) } },
                        )
                    }
                }

                // The accent colour, under the theme it is drawn in. The phone's wallpaper colours win
                // when they are on, so there the swatches are faded and the line says why.
                item(key = "accent") {
                    AccentRow(
                        selected = accent,
                        wallpaper = touch && dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onPick = { scope.launch { settings.setAccent(it) } },
                        custom = customAccent,
                        onCustom = { argb -> scope.launch { settings.setCustomAccent(argb) } },
                    )
                }

                // The letters, the same stepper on both devices: three faces the system already has.
                item(key = "typeface") {
                    StepperRow(
                        title = s.settingsTypeface,
                        subtitle = s.settingsTypefaceDetail,
                        value = typeface.label,
                        icon = TmIcons.TextSize,
                        canDecrease = typeface.ordinal > 0,
                        canIncrease = typeface.ordinal < com.tmplayer.data.AppTypeface.entries.lastIndex,
                        onStep = { direction ->
                            com.tmplayer.data.AppTypeface.entries.getOrNull(typeface.ordinal + direction)?.let { next ->
                                scope.launch { settings.setTypeface(next) }
                            }
                        },
                    )
                }
                item(key = "high_contrast") {
                    ToggleRow(
                        title = s.settingsHighContrast,
                        subtitle = s.settingsHighContrastDetail,
                        icon = TmIcons.Theme,
                        checked = highContrast,
                        onToggle = { scope.launch { settings.setHighContrast(!highContrast) } },
                    )
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

                // The interface size (CP59), the same stepper on both devices: four sizes are too
                // many words for segments across a phone, and Left and Right already mean "change
                // this" on a TV. The whole screen, this row included, grows or shrinks as it moves.
                item {
                    StepperRow(
                        title = s.interfaceSize,
                        subtitle = s.interfaceSizeDetail,
                        value = uiScale.label,
                        icon = TmIcons.TextSize,
                        canDecrease = uiScale.ordinal > 0,
                        canIncrease = uiScale.ordinal < UiScale.entries.lastIndex,
                        onStep = { direction -> scope.launch { settings.setUiScale(uiScale.step(direction)) } },
                    )
                }

                // CP68: the two lightening switches the low-end device card recommends, each its own
                // row so either can be turned back on its own.
                item {
                    ToggleRow(
                        title = s.settingsLighterPictures,
                        modifier = Modifier.jumpTarget(jumps.getValue(SetupStep.Performance)),
                        subtitle = if (lighterPictures) s.settingsLighterPicturesOn else s.settingsLighterPicturesOff,
                        icon = TmIcons.Image,
                        checked = lighterPictures,
                        onToggle = { scope.launch { settings.setLighterPictures(!lighterPictures) } },
                    )
                }
                item {
                    ToggleRow(
                        title = s.settingsReduceMotion,
                        subtitle = if (reduceMotion) s.settingsReduceMotionOn else s.settingsReduceMotionOff,
                        icon = TmIcons.Frames,
                        checked = reduceMotion,
                        onToggle = { scope.launch { settings.setReduceMotion(!reduceMotion) } },
                    )
                }
                // Back to the defaults, with Undo in its place for a few seconds after.
                item(key = "appearance_defaults") {
                    // One row whose words change, so the remote stays on it through both presses.
                    val taken = appearanceUndo
                    ActionRow(
                        title = if (taken == null) s.settingsAppearanceDefaults else s.settingsAppearanceUndo,
                        subtitle = if (taken == null) s.settingsAppearanceDefaultsDetail else s.settingsAppearanceDefaultsDone,
                        icon = TmIcons.Refresh,
                        onClick = {
                            if (taken == null) {
                                scope.launch { appearanceUndo = settings.resetAppearance() }
                            } else {
                                appearanceUndo = null
                                scope.launch { settings.restoreAppearance(taken) }
                            }
                        },
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
                        icon = TmIcons.Play,
                        checked = autoplayNext,
                        onToggle = { scope.launch { settings.setAutoplayNext(!autoplayNext) } },
                    )
                }
                // Hardware first, or the app's own software decoder for a device whose decoder
                // draws broken frames. The player's error sheet offers the other one too.
                item {
                    ActionRow(
                        title = s.settingsDecoder,
                        subtitle = videoDecoder.label,
                        icon = TmIcons.Play,
                        onClick = { pickingDecoder = true },
                    )
                }
                item {
                    ToggleRow(
                        title = s.settingsDetailFirst,
                        subtitle = if (detailFirst) s.settingsDetailFirstOn(device) else s.settingsDetailFirstOff(device),
                        icon = TmIcons.Info,
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
                // Low storage mode is on the Storage page, with the rest of what decides the room
                // TMPlayer takes (user, 2026-10-22).
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
                // On by default; the player's menu has the same line for the video playing now.
                item {
                    ToggleRow(
                        title = s.playerEvenVolume,
                        subtitle = if (evenVolume) s.settingsEvenVolumeOn else s.settingsEvenVolumeOff,
                        icon = ImageVector.vectorResource(R.drawable.ic_volume),
                        checked = evenVolume,
                        onToggle = { scope.launch { settings.setEvenVolume(!evenVolume) } },
                    )
                }
                // The phone only: a television has no screen to turn off and nothing else to switch to.
                // Off by default, so leaving the player pauses it as it always has; read as the
                // player is left, so it applies to the video playing now.
                if (touch) {
                    item {
                        ToggleRow(
                            title = s.settingsBackgroundAudio,
                            subtitle = if (backgroundAudio) s.settingsBackgroundAudioOn else s.settingsBackgroundAudioOff,
                            icon = TmIcons.Audio,
                            checked = backgroundAudio,
                            onToggle = { scope.launch { settings.setBackgroundAudio(!backgroundAudio) } },
                        )
                    }
                }
                // Phone only (a television never enters by itself), and only where Android has the
                // feature. Followed by the player, so it applies to the video open now (HX9).
                if (touch && pipAvailable) {
                    item {
                        ToggleRow(
                            title = s.settingsPictureInPictureLeave,
                            subtitle = if (pipOnLeave) s.settingsPictureInPictureLeaveOn else s.settingsPictureInPictureLeaveOff,
                            icon = ImageVector.vectorResource(R.drawable.ic_pip),
                            checked = pipOnLeave,
                            onToggle = { scope.launch { settings.setPictureInPictureOnLeave(!pipOnLeave) } },
                        )
                    }
                }
                // The soundtrack a video opens in when neither the file nor its series has one saved.
                item {
                    ActionRow(
                        title = s.settingsAudioLanguage,
                        subtitle = trackPrefs.audioSummary,
                        icon = ImageVector.vectorResource(R.drawable.ic_audio_language),
                        onClick = { trackPicker = TrackLanguagePicker.Audio },
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
                // Off, 3, 5 or 10 seconds, stepped like the timeout above so the remote needs no sheet.
                item {
                    val choices = ResumeRewind.CHOICES_MS
                    StepperRow(
                        title = s.settingsResumeRewind,
                        subtitle = s.settingsResumeRewindDetail,
                        value = ResumeRewind.label(resumeRewindMs),
                        icon = TmIcons.History,
                        canDecrease = resumeRewindMs != choices.first(),
                        canIncrease = resumeRewindMs != choices.last(),
                        onStep = { direction ->
                            scope.launch {
                                settings.setResumeRewindMs(TouchPrefs.step(choices, resumeRewindMs, direction))
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
                        subtitle = subtitleStyle.summary(),
                        icon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                        onClick = { stylingSubtitles = true },
                    )
                }
                // Which subtitles a video opens with, when neither the file nor its series says.
                item {
                    ActionRow(
                        title = s.settingsSubtitleMode,
                        subtitle = trackPrefs.mode.label,
                        icon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                        onClick = { trackPicker = TrackLanguagePicker.Mode },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsSubtitleLanguage,
                        subtitle = trackPrefs.subtitleSummary,
                        icon = TmIcons.Language,
                        onClick = { trackPicker = TrackLanguagePicker.Subtitle },
                    )
                }
                // What each series learnt from the player, and Forget: the per series memory
                // above the languages chosen here, made visible.
                item {
                    ActionRow(
                        title = s.settingsLearntLanguages,
                        subtitle = if (learntLanguages.isEmpty()) {
                            s.settingsLearntLanguagesEmpty
                        } else {
                            s.settingsLearntLanguagesCount(learntLanguages.size)
                        },
                        icon = TmIcons.History,
                        onClick = { showingLearnt = true },
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
                    // First on the page, ahead of the player's own gestures: it governs every
                    // tick in the app, the switches, choices, PIN pad and sign in as well.
                    item {
                        ToggleRow(
                            title = s.settingsHaptics,
                            subtitle = s.settingsHapticsDetail,
                            icon = TmIcons.Vibration,
                            checked = touchPrefs.haptics,
                            onToggle = { update { it.copy(haptics = !it.haptics) } },
                        )
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
                            icon = TmIcons.ChevronRight,
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
                        icon = TmIcons.List,
                        onClick = { editingSizes = true },
                    )
                }

                // What opens first, in the same card as which videos show: a heading of its own
                // stood over a single row.
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
                            icon = TmIcons.Close,
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

                // The lists TMPlayer keeps: recent searches first (moved here from Playback, as
                // they are a list kept like the others, not a way of playing), then a clear for
                // each list that has anything in it.
                sectionTitle { s.settingsLists }
                item {
                    // Off forgets the searches already kept, which is what switching it off is for.
                    ToggleRow(
                        title = s.settingsRememberSearches,
                        subtitle = if (rememberSearches) s.settingsRememberSearchesOn else s.settingsRememberSearchesOff,
                        icon = TmIcons.Search,
                        checked = rememberSearches,
                        onToggle = { scope.launch { settings.setRememberSearches(!rememberSearches) } },
                    )
                }
                // Resume across devices: opt in, explained in full on the row itself, since it
                // writes a message to the viewer's own Saved Messages.
                item {
                    ToggleRow(
                        title = s.settingsResumeSync,
                        subtitle = if (resumeSync) s.settingsResumeSyncOn else s.settingsResumeSyncOff,
                        icon = TmIcons.History,
                        checked = resumeSync,
                        onToggle = {
                            scope.launch {
                                val on = !resumeSync
                                settings.setResumeSync(on)
                                // The switch and its line say it is on; nothing more to say.
                                if (on) com.tmplayer.data.ResumeSync.syncSoon(settings, afterMs = 0)
                            }
                        },
                    )
                }
                // Always listed, so the way back to a hidden chat is where the viewer looks for it,
                // and an empty list says how a chat gets on it.
                item {
                    ActionRow(
                        title = s.settingsHiddenChats,
                        subtitle = if (hiddenChats.isEmpty()) s.settingsHiddenChatsNone else s.settingsHiddenChatsDetail(hiddenChats.size),
                        icon = TmIcons.VisibilityOff,
                        onClick = { if (hiddenChats.isNotEmpty()) prompt = Prompt.HiddenChats },
                    )
                }
                if (favorites.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearFavourites,
                            subtitle = s.settingsClearFavouritesDetail(favorites.size),
                            icon = TmIcons.Close,
                            onClick = { prompt = Prompt.ClearFavorites },
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearHistory,
                            subtitle = s.settingsClearHistoryDetail(history.size),
                            icon = TmIcons.Close,
                            onClick = { prompt = Prompt.ClearHistory },
                        )
                    }
                }
                if (watchedList.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = s.settingsClearWatched,
                            subtitle = s.settingsClearWatchedDetail(watchedList.size),
                            icon = TmIcons.Close,
                            onClick = { prompt = Prompt.ClearWatched },
                        )
                    }
                }

                // ---- backup -----------------------------------------------------------------------------

                // To a file the viewer keeps, and back: settings, lists and resume points, never the
                // Telegram sign in. See BackupActions for where the file goes on a box with no picker.
                sectionTitle { s.backupSection }
                item {
                    ActionRow(
                        title = s.backupBackUp,
                        subtitle = s.backupBackUpDetail,
                        icon = TmIcons.Download,
                        onClick = backup.backUp,
                    )
                }
                item {
                    ActionRow(
                        title = s.backupRestore,
                        subtitle = s.backupRestoreDetail,
                        icon = TmIcons.Refresh,
                        onClick = backup.restore,
                    )
                }

                // ---- posters and details --------------------------------------------------------------
                onlineMetadataSection(
                    onEditKey = { editingTmdbKey = true },
                    onEditOmdbKey = { editingOmdbKey = true },
                    toggleModifier = Modifier.jumpTarget(jumps.getValue(SetupStep.Posters)),
                )
            }

            // Which rows Home shows, each on by default, and where Recently added (and the carousel,
            // drawn from it) takes its videos from (users, 2026-10-27).
            SettingsPage.Home -> {
                sectionTitle { s.settingsHomeRows }
                item(key = "home_carousel") {
                    ToggleRow(
                        title = s.settingsHomeCarousel,
                        modifier = Modifier.focusRequester(landing),
                        subtitle = if (homeLayout.carousel) s.settingsHomeCarouselOn else s.settingsHomeCarouselOff,
                        icon = TmIcons.Home,
                        checked = homeLayout.carousel,
                        onToggle = { scope.launch { settings.setHomeCarousel(!homeLayout.carousel) } },
                    )
                }
                item(key = "home_continue") {
                    ToggleRow(
                        title = s.settingsHomeContinue,
                        subtitle = if (homeLayout.continueWatching) s.settingsHomeContinueOn else s.settingsHomeContinueOff,
                        icon = TmIcons.History,
                        checked = homeLayout.continueWatching,
                        onToggle = { scope.launch { settings.setHomeContinue(!homeLayout.continueWatching) } },
                    )
                }
                item(key = "home_recent") {
                    ToggleRow(
                        title = s.settingsHomeRecent,
                        subtitle = if (homeLayout.recentlyAdded) s.settingsHomeRecentOn else s.settingsHomeRecentOff,
                        icon = TmIcons.Grid,
                        checked = homeLayout.recentlyAdded,
                        onToggle = { scope.launch { settings.setHomeRecent(!homeLayout.recentlyAdded) } },
                    )
                }
                item(key = "home_starred_rows") {
                    ToggleRow(
                        title = s.settingsHomeStarredRows,
                        subtitle = if (homeLayout.starredRows) s.settingsHomeStarredRowsOn else s.settingsHomeStarredRowsOff,
                        icon = TmIcons.StarOutline,
                        checked = homeLayout.starredRows,
                        onToggle = { scope.launch { settings.setHomeStarredRows(!homeLayout.starredRows) } },
                    )
                }
                sectionTitle { s.settingsHomeRecentFrom }
                item(key = "home_recent_source") {
                    ActionRow(
                        title = s.settingsHomeRecentFrom,
                        subtitle = if (homeLayout.chosen) s.settingsHomeSourceChosen else s.settingsHomeSourceAuto,
                        icon = TmIcons.List,
                        onClick = { pickingHomeSource = true },
                    )
                }
                if (homeLayout.chosen) {
                    item(key = "home_recent_chats") {
                        // Only the chosen chats still in the list count: one left or deleted is skipped on Home too.
                        val count = if (chats.isEmpty()) homeLayout.recentChats.size else chats.count { it.id in homeLayout.recentChats }
                        ActionRow(
                            title = s.settingsHomeChooseChats,
                            subtitle = s.settingsHomeSourceChosenDetail(count = count),
                            icon = TmIcons.Check,
                            onClick = { pickingHomeChats = true },
                        )
                    }
                }
                settingsNote { SettingsNoteText(s.settingsHomeRecentFromNote) }
            }

            // What TMPlayer puts on the Android TV home screen, a page of its own rather than two rows
            // in Playback: neither changes how a video plays. Its category row shows only where the
            // launcher has one of the two (see the list below).
            SettingsPage.TvHome -> {
                // The Android TV home screen's "Play next" row. Only where there is one: a phone, a Fire TV
                // and a Fire tablet have none, and a switch for it there would do nothing.
                if (watchNextSupported) {
                    item {
                        ToggleRow(
                            title = s.settingsWatchNext,
                            modifier = Modifier.focusRequester(landing),
                            subtitle = if (watchNextOn) s.settingsWatchNextOn else s.settingsWatchNextOff,
                            icon = TmIcons.History,
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
                // TMPlayer's own rows of cards on the TV home screen. Only where the launcher has
                // channels, which rules out phones and Fire TV.
                if (tvChannelsSupported) {
                    item {
                        ToggleRow(
                            title = s.settingsTvChannels,
                            modifier = if (watchNextSupported) Modifier else Modifier.focusRequester(landing),
                            subtitle = if (tvChannelsOn) s.settingsTvChannelsOn else s.settingsTvChannelsOff,
                            icon = TmIcons.Home,
                            checked = tvChannelsOn,
                            // Turning it off takes the channels away (TvHomeChannels follows the
                            // setting); turning it on fills them at the next refresh of Home.
                            onToggle = { scope.launch { settings.setTvChannels(!tvChannelsOn) } },
                        )
                    }
                }
            }

            // Every notification TMPlayer may post, one switch each (CP76). A phone's only: a TV
            // posts none, its launcher rows do that job. Turning one on is where Android 13 asks
            // for the permission, never at launch.
            SettingsPage.Notifications -> {
                val noticeRows = buildList {
                    add(Triple(Notice.Downloads, s.settingsAlertDownloads, s.settingsAlertDownloadsDetail))
                    add(Triple(Notice.NewEpisodes, s.settingsAlertEpisodes, s.settingsAlertEpisodesDetail))
                    add(Triple(Notice.NewVideos, s.settingsNewVideos, s.settingsNewVideosDetail))
                    add(Triple(Notice.MissingPieces, s.settingsAlertPieces, s.settingsAlertPiecesDetail))
                    add(Triple(Notice.LowStorage, s.settingsAlertLowStorage, s.settingsAlertLowStorageDetail))
                    if (Updates.enabled) add(Triple(Notice.UpdateReady, s.settingsAlertUpdate, s.settingsAlertUpdateDetail))
                    if (SupportReminder.enabled && !supportCounters.finished) {
                        add(Triple(Notice.Support, s.settingsSponsorReminders, s.settingsSponsorRemindersDetail))
                    }
                }
                noticeRows.forEachIndexed { index, (notice, title, detail) ->
                    item(key = "notice-${notice.name}") {
                        NoticeToggleRow(
                            notice = notice,
                            title = title,
                            subtitle = detail,
                            modifier = if (index == 0) Modifier.focusRequester(landing) else Modifier,
                            onBlocked = { toast(s.notifyBlocked) },
                        )
                    }
                }
            }

            SettingsPage.Storage -> {
                // Settings only, and the same Free up space the Downloads page has (user,
                // 2026-10-22): the ring with what TMPlayer uses and what is free, and the button,
                // which opens its review over the Downloads page. The rows that cleared one part
                // each (Recently played, Clear cache, pictures, Clear all) are groups in that review.
                val measured = splitRead && disk.totalBytes > 0
                val nothingToFree = split.downloadBytes + split.cachedBytes + split.pictureBytes + split.unfinishedBytes <= 0
                // On a television the remote lands on Free up space, or, when there is nothing to
                // free and the button is off, on the first switch under it.
                val landOnFreeUp = measured && !nothingToFree
                settingsNote {
                    StorageCard(
                        split = split,
                        disk = disk,
                        measured = measured,
                        nothingToFree = nothingToFree,
                        onFreeUp = onFreeUpSpace,
                        focus = if (landOnFreeUp) landing else null,
                    )
                }
                // Here rather than among the downloads it acts on: it is a setting, and the
                // Downloads page is the list. Turning it on is asked first; off deletes nothing.
                item {
                    ToggleRow(
                        title = s.downloadsRemoveAfterWatching,
                        subtitle = s.downloadsRemoveAfterWatchingBody,
                        icon = TmIcons.Delete,
                        checked = removeAfterWatching,
                        modifier = if (landOnFreeUp) Modifier else Modifier.focusRequester(landing),
                        onToggle = {
                            if (removeAfterWatching) {
                                scope.launch { settings.setRemoveAfterWatching(false) }
                            } else {
                                prompt = Prompt.RemoveAfterWatching
                            }
                        },
                    )
                }
                // Streams through a rolling window of the video, for a device with less room than
                // the film. Its subtitle says the cost: going back fetches again, and a short pause
                // comes now and then as the window moves on.
                item {
                    ToggleRow(
                        title = s.settingsLowStorage,
                        modifier = Modifier.jumpTarget(jumps.getValue(SetupStep.Storage)),
                        subtitle = if (lowStorage) s.settingsLowStorageOn else s.settingsLowStorageOff,
                        icon = TmIcons.Archive,
                        checked = lowStorage,
                        onToggle = { scope.launch { settings.setLowStorageMode(!lowStorage) } },
                    )
                }
                // A phone's row only. A television is on the wall and a stick is behind it, both plugged
                // into a network that is Wi-Fi or a cable and never a data allowance somebody pays for by
                // the gigabyte, so on a TV this asks the viewer to rule out something that cannot happen.
                if (MeteredPolicy.wifiOnlyOffered(television = !touch)) {
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
                // Beside it, a phone's row only for the same reason: a stream on mobile data fetched
                // only a few minutes ahead, so a film stopped early has not cost most of its size.
                if (MeteredPolicy.saveMobileDataOffered(television = !touch)) {
                    item {
                        ToggleRow(
                            title = s.settingsSaveMobileData,
                            modifier = Modifier.jumpTarget(jumps.getValue(SetupStep.MobileData)),
                            subtitle = if (saveMobileData) {
                                s.settingsSaveMobileDataOn
                            } else {
                                s.settingsSaveMobileDataOff
                            },
                            icon = TmIcons.Phone,
                            checked = saveMobileData,
                            onToggle = { scope.launch { settings.setSaveMobileData(!saveMobileData) } },
                        )
                    }
                }
            }

            SettingsPage.Lock -> {
                if (!appLock.pinSet) {
                    item {
                        ActionRow(
                            title = s.lockSetPin,
                            subtitle = s.lockSetPinDetail,
                            icon = TmIcons.Lock,
                            modifier = Modifier.focusRequester(landing),
                            onClick = { pinFlow = PinFlow.Set },
                        )
                    }
                } else {
                    item {
                        ActionRow(
                            title = s.lockChangePin,
                            subtitle = s.lockChangePinDetail,
                            icon = TmIcons.Lock,
                            modifier = Modifier.focusRequester(landing),
                            onClick = { pinFlow = PinFlow.ChangeCheck },
                        )
                    }
                    item {
                        ToggleRow(
                            title = s.lockOnOpen,
                            subtitle = s.lockOnOpenDetail,
                            icon = TmIcons.Remote,
                            checked = appLock.onOpen,
                            onToggle = {
                                val on = !appLock.onOpen
                                // Whoever switches it on has just given the PIN to reach this page.
                                if (on) AppLockSession.unlock()
                                scope.launch { settings.setLockOnOpen(on) }
                            },
                        )
                    }
                }
                item {
                    ToggleRow(
                        title = s.lockKids,
                        subtitle = AppLockWords.kidsDetail(appLock),
                        icon = TmIcons.Group,
                        checked = appLock.kids,
                        onToggle = {
                            when {
                                // The lock is only as good as the PIN that guards its switch.
                                !appLock.pinSet -> pinFlow = PinFlow.SetForKids
                                appLock.kids -> scope.launch { settings.setKidsLock(false) }
                                else -> {
                                    scope.launch { settings.setKidsLock(true) }
                                    if (appLock.kidsChats.isEmpty()) pickingKidsChats = true
                                }
                            }
                        },
                    )
                }
                item {
                    ActionRow(
                        title = s.lockAllowedChats,
                        subtitle = s.lockAllowedChatsDetail(count = appLock.kidsChats.size),
                        icon = TmIcons.List,
                        onClick = { pickingKidsChats = true },
                    )
                }
                if (appLock.pinSet) {
                    item {
                        ActionRow(
                            title = s.lockRemovePin,
                            subtitle = s.lockRemovePinDetail,
                            icon = TmIcons.Close,
                            onClick = { pinFlow = PinFlow.Remove },
                        )
                    }
                }
                settingsNote {
                    Text(
                        s.lockForgot,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        modifier = Modifier.padding(
                            top = 12.dp,
                            start = if (touch) 16.dp else 0.dp,
                            end = if (touch) 16.dp else 0.dp,
                        ),
                    )
                }
            }

            SettingsPage.About -> {
                // ---- version -------------------------------------------------------------------------

                // Not in a build that a store keeps up to date (Updates.enabled, the F-Droid
                // build): the store is what updates it, and the version is still on the About
                // row and the About screen. The page then lands on the first help row instead.
                if (Updates.enabled) {
                    sectionTitle { s.settingsVersion }
                    updateState.release?.let { offered ->
                        item {
                            ActionRow(
                                title = UpdateWords.settingsRow(offered),
                                modifier = Modifier.focusRequester(landing),
                                subtitle = s.settingsUpdateDetail(s.formatter.bytes(Updates.apkFor(offered)?.size ?: 0L)),
                                icon = TmIcons.Refresh,
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
                            icon = TmIcons.Refresh,
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
                    // GitHub pre-releases (RL1). On asks first, with what it means; off is not asked.
                    item {
                        ToggleRow(
                            title = s.settingsTestVersions,
                            subtitle = if (updateTestVersions) s.settingsTestVersionsOn else s.settingsTestVersionsOff,
                            icon = TmIcons.Warning,
                            checked = updateTestVersions,
                            onToggle = {
                                if (updateTestVersions) {
                                    scope.launch {
                                        settings.setUpdateTestVersions(false)
                                        Updates.testVersionsSwitched(on = false, skipped = settings.updatePrefs.skippedVersion())
                                    }
                                } else {
                                    prompt = Prompt.TestVersions
                                }
                            },
                        )
                    }
                    // "Install updates by itself" (HX5): Android 12 and later only, where Android can
                    // install an update without asking. Off by default; nothing more to answer.
                    if (com.tmplayer.data.AutoUpdater.supported) {
                        item {
                            // Read on each pass: the install permission may have changed meanwhile.
                            val mayInstall = Updates.canInstall(context)
                            ToggleRow(
                                title = s.settingsAutoInstall,
                                subtitle = when {
                                    !updateAutoInstall -> s.settingsAutoInstallOff
                                    !mayInstall -> s.settingsAutoInstallBlocked
                                    else -> s.settingsAutoInstallOn
                                },
                                icon = TmIcons.Refresh,
                                checked = updateAutoInstall,
                                onToggle = {
                                    scope.launch {
                                        settings.setUpdateAutoInstall(!updateAutoInstall)
                                        runCatching { com.tmplayer.data.BackgroundChecks.schedule(context) }
                                    }
                                },
                            )
                        }
                    }
                }

                // ---- help ----------------------------------------------------------------------------

                sectionTitle { s.settingsHelp }
                // A proxy (CP77), for a network where Telegram is blocked; the same screen the
                // connecting help opens before sign in.
                item {
                    val proxyOn by com.tmplayer.data.Td.proxyOn.collectAsStateWithLifecycle()
                    ActionRow(
                        title = s.proxyTitle,
                        subtitle = if (proxyOn) s.proxyInUse else s.proxySettingsDetailOff,
                        icon = TmIcons.Wifi,
                        onClick = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsTour,
                        subtitle = s.settingsTourBody,
                        icon = TmIcons.Info,
                        onClick = { scope.launch { settings.openTour() } },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsWhatsNew,
                        subtitle = s.settingsWhatsNewDetail(WhatsNew.VERSION),
                        icon = TmIcons.StarOutline,
                        onClick = { whatsNew = true },
                    )
                }
                item {
                    ActionRow(
                        title = s.settingsChangelog,
                        subtitle = s.settingsChangelogDetail,
                        icon = TmIcons.List,
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
                // The logs to attach to that report (CP59), scrubbed of names and ids before they are written.
                item {
                    ActionRow(
                        title = s.logsSave,
                        subtitle = s.logsSaveDetail,
                        icon = TmIcons.Logs,
                        onClick = saveLogs,
                    )
                }
                // Hidden until a chat's search turns it on (see DEVELOPER_PHRASE): how the app read
                // the file names of the chat opened last, to check its guesses against real chats.
                if (developerOptions) {
                    item {
                        ActionRow(
                            title = s.logsNameReport,
                            subtitle = s.logsNameReportDetail,
                            icon = TmIcons.Checklist,
                            onClick = saveNameCheck,
                        )
                    }
                }
                item {
                    ActionRow(
                        title = s.aboutPrivacy,
                        subtitle = s.settingsPrivacyDetail(device),
                        icon = TmIcons.Lock,
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
                            icon = TmIcons.Warning,
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
                            icon = TmIcons.Favorite,
                            onClick = { supporting = true },
                        )
                    }
                    // The occasional support notification's switch lives on the Notifications
                    // page with the rest (CP76).
                }

                item {
                    ActionRow(
                        title = s.settingsAbout,
                        subtitle = s.settingsAboutDetail(Updates.installedVersion),
                        icon = TmIcons.Info,
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
                // Who is signed in, first, on the page's own background: a header, not one more
                // row to press, so it sits in line with the rows' icons rather than in a card.
                if (account != null) {
                    settingsNote { AccountHeader(account) }
                }
                item {
                    ActionRow(
                        title = titleOf(SettingsPage.SetupChoices),
                        subtitle = s.setupChoicesSummary,
                        icon = TmIcons.Checklist,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.SetupChoices)),
                        onClick = {
                            cameFromStep = null
                            showPage(SettingsPage.SetupChoices)
                        },
                    )
                }
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
                        icon = TmIcons.Play,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Playback)),
                        onClick = { showPage(SettingsPage.Playback) },
                    )
                }
                item {
                    val style = subtitleStyle.summary()
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
                        title = titleOf(SettingsPage.Home),
                        subtitle = s.settingsPageHomeSummary,
                        icon = TmIcons.Home,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Home)),
                        onClick = { showPage(SettingsPage.Home) },
                    )
                }
                // Android TV and Google TV only: a phone, a Fire TV and a Fire tablet have neither a
                // Play next row nor channels, and a page of switches that do nothing there is noise.
                if (watchNextSupported || tvChannelsSupported) {
                    item {
                        ActionRow(
                            title = titleOf(SettingsPage.TvHome),
                            subtitle = s.settingsPageTvHomeSummary,
                            icon = TmIcons.Home,
                            modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.TvHome)),
                            onClick = { showPage(SettingsPage.TvHome) },
                        )
                    }
                }
                // A phone's only: a TV posts no notifications (CP76).
                if (touch) {
                    item {
                        ActionRow(
                            title = titleOf(SettingsPage.Notifications),
                            subtitle = s.settingsPageNotificationsSummary,
                            icon = TmIcons.Bell,
                            modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Notifications)),
                            onClick = { showPage(SettingsPage.Notifications) },
                        )
                    }
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
                    ActionRow(
                        title = titleOf(SettingsPage.Lock),
                        subtitle = AppLockWords.summary(appLock),
                        icon = TmIcons.Lock,
                        modifier = Modifier.focusRequester(categoryFocus.getValue(SettingsPage.Lock)),
                        onClick = { showPage(SettingsPage.Lock) },
                    )
                }
                // Where the account is signed in, and ending a session (CP83). Inside Settings, so
                // behind its PIN when the lock or the kids lock asks for one.
                item {
                    ActionRow(
                        title = s.settingsDevices,
                        subtitle = s.settingsDevicesDetail,
                        icon = TmIcons.Phone,
                        modifier = Modifier.focusRequester(devicesFocus),
                        onClick = { devicesOpen = true },
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
                        icon = TmIcons.Info,
                        tint = if (offered != null) Tone.caution else Color.Unspecified,
                        modifier = Modifier
                            .focusRequester(categoryFocus.getValue(SettingsPage.About))
                            // A held Down stops here. Sign out is the only row below, and a remote
                            // whose key is held to reach the end of the list should land on a page
                            // to open, not on the way out of the account: one fresh press reaches it.
                            .onPreviewKeyEvent { event ->
                                !touch && event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.repeatCount > 0
                            },
                        onClick = { showPage(SettingsPage.About) },
                    )
                }

                // Last, under the cards rather than in one: the one press here that ends the session.
                settingsNote {
                    SignOutButton(
                        label = s.settingsSignOut,
                        onClick = { prompt = Prompt.SignOut },
                    )
                }
            }
        } }
    }
    }

    val flow = pinFlow
    if (flow != null) {
        val close = { pinFlow = null }
        PinPad(
            settings = settings,
            purpose = when (flow) {
                PinFlow.ChangeCheck, PinFlow.Remove -> PinPurpose.Check
                PinFlow.Set, PinFlow.SetForKids, PinFlow.ChangeChoose -> PinPurpose.Choose
            },
            title = when (flow) {
                PinFlow.ChangeCheck, PinFlow.Remove -> s.lockEnterCurrent
                PinFlow.Set, PinFlow.SetForKids, PinFlow.ChangeChoose -> s.lockNewPin
            },
            onDone = { pin ->
                when (flow) {
                    PinFlow.ChangeCheck -> pinFlow = PinFlow.ChangeChoose
                    PinFlow.Remove -> {
                        close()
                        scope.launch {
                            settings.removePin()
                            toast(s.lockPinRemovedToast)
                        }
                    }
                    PinFlow.Set, PinFlow.ChangeChoose, PinFlow.SetForKids -> {
                        close()
                        scope.launch {
                            settings.setPin(pin)
                            if (flow == PinFlow.SetForKids) {
                                settings.setKidsLock(true)
                                if (appLock.kidsChats.isEmpty()) pickingKidsChats = true
                            }
                            toast(s.lockPinSetToast)
                        }
                    }
                }
            },
            onCancel = close,
            // A forgotten PIN has its way back explained on the page itself.
            showForgot = false,
        )
        return
    }

    if (devicesOpen) {
        com.tmplayer.ui.settings.DevicesScreen(onClose = { devicesOpen = false; devicesClosed = true })
        return
    }
    // Back from Devices on a television lands on its row again.
    LaunchedEffect(devicesClosed) {
        if (devicesClosed && !touch) {
            kotlinx.coroutines.delay(60)
            runCatching { devicesFocus.requestFocus() }
        }
        devicesClosed = false
    }

    if (touch && page != null) {
        // A page: its name along the top beside the round Back, which goes back to the list, the
        // same as the hardware key. The floating bar is the list's, not the page's.
        val pageScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.nestedScroll(pageScroll.nestedScrollConnection),
                topBar = { FloatingPageTitle({ M3Text(titleOf(page!!)) }, pageScroll) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    list()
                    // Only a page long enough to need it shows the handle.
                    FastScroller(if (page == null) listState else pageState)
                }
            }
            FloatingBackButton(TmIcons.ArrowBack, s.aboutBackToSettings, { closePage() }, Modifier.align(Alignment.TopStart))
        }
    } else if (touch) {
        // The list: opened from the profile picture's menu as a page of its own, so it has the
        // pages' floating title and round Back, which goes back where it was opened from.
        val listScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.nestedScroll(listScroll.nestedScrollConnection),
                topBar = { FloatingPageTitle({ M3Text(s.settingsTitle) }, listScroll) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    list()
                    // Only a page long enough to need it shows the handle.
                    FastScroller(if (page == null) listState else pageState)
                }
            }
            FloatingBackButton(TmIcons.ArrowBack, s.commonBack, onBack, Modifier.align(Alignment.TopStart))
        }
    } else {
        list()
    }

    qrLink?.let { (url, what) -> LinkQrDialog(url = url, what = what, onClose = { qrLink = null }) }
    if (supporting) SupportDialog(from = "settings", onClose = { supporting = false })
    if (pickingLanguage) LanguageDialog(settings, onClose = { pickingLanguage = false })
    if (showingLearnt) {
        LearntLanguagesSheet(
            learnt = learntLanguages,
            onForget = { series -> scope.launch { settings.forgetLearntLanguages(series.key) } },
            onForgetAll = { scope.launch { settings.forgetAllLearntLanguages() } },
            onClose = { showingLearnt = false },
        )
    }
    if (stylingSubtitles) {
        SubtitleStyleDialog(
            style = subtitleStyle,
            onChange = { next -> scope.launch { settings.setSubtitleStyle(next) } },
            onClose = { stylingSubtitles = false },
        )
    }
    if (pickingDecoder) {
        DecoderSheet(
            current = videoDecoder,
            onPick = { next -> scope.launch { settings.setVideoDecoder(next) } },
            onClose = { pickingDecoder = false },
        )
    }
    trackPicker?.let { picker ->
        TrackLanguageSheet(
            picker = picker,
            prefs = trackPrefs,
            onChange = { next -> scope.launch { settings.setTrackPreferences(next) } },
            onClose = { trackPicker = null },
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
    if (editingOmdbKey) OmdbKeyDialog(onClose = { editingOmdbKey = false })

    if (pickingHomeSource) {
        HomeSourceSheet(
            chosen = homeLayout.chosen,
            onPick = { source ->
                pickingHomeSource = false
                scope.launch { settings.setHomeRecentSource(source) }
                // Chosen with nothing chosen yet: the picker comes up at once.
                if (source == com.tmplayer.data.RecentSource.Chosen && homeLayout.recentChats.isEmpty()) pickingHomeChats = true
            },
            onClose = { pickingHomeSource = false },
        )
    }
    if (pickingHomeChats) {
        HomeChatsSheet(
            chats = chats,
            chosen = homeLayout.recentChats,
            onChange = { next -> scope.launch { settings.setHomeRecentChats(next) } },
            onClose = { pickingHomeChats = false },
        )
    }

    if (pickingKidsChats) {
        KidsChatsSheet(
            chats = chats,
            chosen = appLock.kidsChats,
            onToggle = { id ->
                val next = if (id in appLock.kidsChats) appLock.kidsChats - id else appLock.kidsChats + id
                scope.launch { settings.setKidsChats(next) }
            },
            onClose = { pickingKidsChats = false },
        )
    }

    when (prompt) {
        Prompt.RemoveAfterWatching -> TvConfirm(
            title = s.confirmRemoveAfterWatchingTitle,
            message = s.confirmRemoveAfterWatchingMessage,
            detail = s.confirmRemoveAfterWatchingDetail,
            confirmLabel = s.confirmTurnOn,
            onConfirm = {
                prompt = null
                scope.launch { settings.setRemoveAfterWatching(true) }
            },
            onDismiss = { prompt = null },
        )

        Prompt.TestVersions -> TvConfirm(
            title = s.confirmTestVersionsTitle,
            message = s.confirmTestVersionsMessage,
            detail = s.confirmTestVersionsDetail,
            confirmLabel = s.confirmTurnOn,
            onConfirm = {
                prompt = null
                scope.launch {
                    settings.setUpdateTestVersions(true)
                    Updates.testVersionsSwitched(on = true, skipped = settings.updatePrefs.skippedVersion())
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
                    settings.clearFavorites(allowedChats)
                    toast(s.settingsFavouritesCleared(count))
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.HiddenChats -> {
            // The last one shown closes the list, which has nothing left to say.
            LaunchedEffect(hiddenChats.isEmpty()) { if (hiddenChats.isEmpty()) prompt = null }
            val firstLine = remember { androidx.compose.ui.focus.FocusRequester() }
            ChoiceSheet(
                title = s.settingsHiddenChats,
                note = s.settingsHiddenChatsNote,
                onDismiss = { prompt = null },
                onClose = { prompt = null },
                ignoreRelease = !isTouch(),
            ) {
                ChoiceList {
                    hiddenChats.forEachIndexed { index, chat ->
                        ChoiceLine(
                            chat.title.ifBlank { chat.id.toString() },
                            modifier = if (index == 0) Modifier.focusRequester(firstLine) else Modifier,
                            detail = s.settingsHiddenChatsShow,
                            icon = TmIcons.VisibilityOff,
                        ) {
                            // The line leaving this list is the answer.
                            scope.launch { settings.showChat(chat.id) }
                        }
                    }
                    if (hiddenChats.size > 1) {
                        ChoiceLine(s.settingsHiddenChatsShowAll, icon = TmIcons.Checklist) {
                            prompt = null
                            // The Hidden chats row saying there are none is the answer.
                            scope.launch { settings.showAllChats() }
                        }
                    }
                }
                FocusOnOpen(firstLine)
            }
        }

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
                    runCatching { CrashReports.discardPending() }
                    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
                    // What this account watched is its own as much as the preferences are, and it
                    // lives in a file of its own, so it is cleared on its own, and so is Watch
                    // later, which shares that file.
                    runCatching { watchedStore.clear() }
                    runCatching { watchedStore.watchLater.clear() }
                    // The bookmarks go with it, for the same reason.
                    runCatching { com.tmplayer.data.BookmarkStore(context).clear() }
                    Td.logOut()
                    onLoggedOut()
                }
            },
            onDismiss = {
                prompt = null
                alsoDeleteDownloads = false
            },
        )

        is Prompt.Restore -> {
            val plan = (prompt as Prompt.Restore).plan
            TvConfirm(
                title = s.backupRestoreTitle,
                message = BackupWords.restoreMessage(plan),
                detail = s.backupRestoreUntouched,
                confirmLabel = s.backupRestoreConfirm,
                destructive = false,
                onConfirm = {
                    prompt = null
                    backup.apply(plan.backup)
                },
                onDismiss = { prompt = null },
            )
        }

        null -> Unit
    }

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false; Updates.dismiss() })
    }
}

/**
 * The kids lock's chats (CP57): every chat the account has, in the chat list's order, ticked when
 * allowed. The language picker's sheet, with a tick per line rather than one choice in force, and
 * each press saved as it is made.
 *
 * [chats] is the list as the browse screen has it, which the kids lock itself filters, so the
 * whole list is read here from the store's snapshot when the lock is on and hiding the rest.
 */
@Composable
private fun KidsChatsSheet(
    chats: List<ChatSummary>,
    chosen: Set<Long>,
    onToggle: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val touch = isTouch()
    // The browse list leaves out what the lock hides, so the choice is made from everything the
    // account had at the last sync, with anything newer from the live list added.
    val every by produceState(initialValue = chats, chats) {
        val remembered = runCatching { SettingsStore(context).cachedChatSnapshot() }.getOrNull().orEmpty()
        value = (chats + remembered).distinctBy { it.id }
    }
    val first = remember { FocusRequester() }
    ChoiceSheet(title = s.lockAllowedChats, note = s.lockAllowedChatsNote, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            every.forEachIndexed { index, chat ->
                val ticked = chat.id in chosen
                ChoiceLine(
                    title = chat.title,
                    // A tick on a remote, as the language picker's; a phone's radio would read as
                    // "one of these", so a check stands in its place there.
                    selected = if (touch) null else ticked,
                    icon = if (ticked) TmIcons.Check else null,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                ) { onToggle(chat.id) }
            }
        }
        FocusOnOpen(first)
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
                        TmIcons.ChevronLeft,
                        contentDescription = null,
                        tint = Tone.onFocusFill,
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        TmIcons.ChevronRight,
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
    // A light tick at each snapped size the thumb passes, nothing between (HAPTICS-PLAN row 4).
    val haptic = com.tmplayer.ui.components.rememberHaptics()

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
            onValueChange = { next ->
                val nextLow = SizeFilter.snap(SizeFilter.bytesAt(next.start))
                val nextHigh = SizeFilter.snap(SizeFilter.bytesAt(next.endInclusive))
                if (nextLow != low || nextHigh != high) haptic(com.tmplayer.ui.components.Haptic.FrequentTick)
                range = next
            },
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
                    TmIcons.Refresh,
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
            TmIcons.Refresh,
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

/**
 * The heading over a group of rows: a small, bold, spaced out label in the muted colour, over
 * the group's card. The card is what separates one group from the next, so the label no longer
 * has to shout; a television's is a size up, for the distance.
 */
@Composable
internal fun SectionTitle(text: String) {
    val touch = isTouch()
    SettingsSectionLabel(
        text,
        style = if (touch) M3Theme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        modifier = if (touch) {
            // The 16dp start is the inset the rows inside the card keep, so the label sits over
            // the row's own edge rather than the card's.
            Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
        } else {
            Modifier.padding(start = 4.dp, top = 18.dp, bottom = 2.dp)
        },
    )
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

/**
 * The accent colour: the row's chip, title and line, and under them the swatches, which are the
 * whole control (no words, each named for a screen reader). On a television the row itself takes
 * no focus: Down from Theme lands on the chosen swatch's row, and Left and Right walk the colours.
 * With the wallpaper's colours in use ([wallpaper]) the swatches are faded and take nothing.
 */
@Composable
private fun AccentRow(
    selected: com.tmplayer.data.Accent,
    wallpaper: Boolean,
    onPick: (com.tmplayer.data.Accent) -> Unit,
    custom: Int? = null,
    onCustom: ((Int) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (touch) {
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                } else {
                    Modifier
                        .clip(RoundedCornerShape(Corner.Large))
                        .background(Tone.surface)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIconChip(TmIcons.Accent)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.settingsAccent,
                    style = if (touch) M3Theme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    color = Tone.text,
                )
                Text(
                    if (wallpaper) s.settingsAccentWallpaper else s.settingsAccentDetail,
                    style = if (touch) M3Theme.typography.bodyMedium else MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                )
            }
        }
        com.tmplayer.ui.components.AccentSwatches(
            selected = selected,
            onPick = onPick,
            enabled = !wallpaper,
            size = if (touch) 30.dp else 34.dp,
            modifier = Modifier.padding(start = 46.dp, top = 8.dp),
            custom = custom,
            onCustom = onCustom,
        )
    }
}

/**
 * What the device holds and the one Free up space, the same [StorageOverview] the Downloads page
 * draws from the same measurement, so the two cannot disagree; then the bargain this device keeps.
 */
@Composable
private fun StorageCard(
    split: StorageSplit,
    disk: DiskInfo,
    measured: Boolean,
    nothingToFree: Boolean,
    onFreeUp: () -> Unit,
    focus: FocusRequester?,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
        shape = if (touch) M3Theme.shapes.large else RoundedCornerShape(Corner.Large),
    ) {
        Column(Modifier.padding(if (touch) 16.dp else 20.dp)) {
            com.tmplayer.ui.components.StorageOverview(
                figures = split.chart(disk),
                measured = measured,
                nothingToFree = nothingToFree,
                onFreeUp = onFreeUp,
                focus = focus,
            )
            Spacer(Modifier.height(if (touch) 12.dp else 16.dp))
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
internal fun TickRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val tick = com.tmplayer.ui.components.rememberToggleTick()
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
                onValueChange = { tick(!checked); onToggle() },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        M3Text(label, style = M3Theme.typography.bodyMedium, color = Tone.text)
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
    val step = com.tmplayer.ui.components.rememberStepTick()

    if (touch) {
        ListItem(
            headlineContent = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            // Not cut to a line count: beside the two arrows a phone held upright has room for a
            // few words a line, and three lines stopped "Interface size" halfway through a sentence.
            supportingContent = { M3Text(s.settingsValueAndDetail(value, subtitle)) },
            leadingContent = { SettingIconChip(icon) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.tmplayer.ui.components.IconTip(s.settingsStepDown) {
                        IconButton(onClick = { step(); onStep(-1) }, enabled = canDecrease) {
                            M3Icon(TmIcons.ChevronDown, contentDescription = s.settingsStepDown)
                        }
                    }
                    com.tmplayer.ui.components.IconTip(s.settingsStepUp) {
                        IconButton(onClick = { step(); onStep(1) }, enabled = canIncrease) {
                            M3Icon(TmIcons.ChevronUp, contentDescription = s.settingsStepUp)
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
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
            .heightIn(min = 64.dp)
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
        SettingIconChip(icon, inverted = focused)
        Spacer(Modifier.size(16.dp))
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
            TmIcons.ChevronLeft,
            contentDescription = null,
            tint = if (canDecrease) onSurface else dim,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(12.dp))
        Icon(
            TmIcons.ChevronRight,
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
            color = Color(style.colour.argb),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            fontFamily = when (style.face) {
                com.tmplayer.player.SubtitleFace.Default -> null
                com.tmplayer.player.SubtitleFace.Serif -> androidx.compose.ui.text.font.FontFamily.Serif
                com.tmplayer.player.SubtitleFace.Monospace -> androidx.compose.ui.text.font.FontFamily.Monospace
            },
            fontWeight = if (style.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            style = TextStyle(shadow = previewShadow(style)),
            modifier = Modifier
                .padding(bottom = maxHeight * style.position.bottomFraction, start = 12.dp, end = 12.dp)
                .then(if (style.box) Modifier.background(Color(style.boxLevel.argb)) else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The edge as Compose can draw it in a preview: a tight dark halo for the outline, a soft offset
 * shadow, or nothing. With a box the box is the edge, as in the player.
 */
private fun previewShadow(style: SubtitleStyle): Shadow? = when {
    style.box -> null
    style.edge == com.tmplayer.player.SubtitleEdge.Outline -> Shadow(Color.Black, blurRadius = 6f)
    style.edge == com.tmplayer.player.SubtitleEdge.Shadow ->
        Shadow(Color.Black.copy(alpha = 0.8f), offset = androidx.compose.ui.geometry.Offset(3f, 3f), blurRadius = 4f)
    else -> null
}

/**
 * The signed in account at the top of Settings, above the cards, from the start: the picture, with
 * the name and @username beside it.
 */
@Composable
private fun AccountHeader(account: com.tmplayer.data.Account) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        com.tmplayer.ui.components.MediaPreview(
            miniThumbnail = account.miniThumbnail,
            thumbnailFileId = account.photoFileId,
            fallbackLabel = account.name,
            modifier = Modifier.size(56.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                account.name,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.username.isNotBlank()) {
                // Isolated left to right, or an Arabic or Persian interface draws "name@".
                Text(
                    "\u2066@${account.username}\u2069",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Sign out, at the very end of Settings: a full width button in the danger colour, since it is the
 * one press here that ends the session and empties the app. It still asks first.
 */
@Composable
private fun SignOutButton(label: String, onClick: () -> Unit) {
    // A remote has no pointer to show where it is, and Material's button barely changes on focus:
    // on a television it stays dimmed until the remote is on it, then fills and takes a ring, so
    // nobody presses Sign out without seeing that it is the button about to be pressed.
    val tv = !isTouch()
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        androidx.compose.material3.Button(
            onClick = onClick,
            interactionSource = interactions,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(if (tv && focused) Modifier.border(3.dp, Tone.text, CircleShape) else Modifier),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (tv && !focused) Tone.danger.copy(alpha = 0.4f) else Tone.danger,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
            ),
        ) {
            androidx.compose.material3.Icon(TmIcons.SignOut, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            M3Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
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
    val iconTint = if (tint == Color.Unspecified) Tone.accent else tint
    FocusRow(
        modifier = modifier,
        onClick = onClick,
        headline = {
            M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supporting = {
            M3Text(subtitle, maxLines = SUPPORTING_LINES, overflow = TextOverflow.Ellipsis)
        },
        leading = { SettingIconChip(icon, tint = iconTint) },
    ) { focused ->
        SettingIconChip(icon, tint = iconTint, inverted = focused)
        Spacer(Modifier.size(16.dp))
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
    // Every switch in Settings answers the thumb (a phone only; nothing on a television).
    val tick = com.tmplayer.ui.components.rememberToggleTick()
    val toggle = { tick(!checked); onToggle() }
    FocusRow(
        modifier = modifier,
        onClick = toggle,
        headline = { M3Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = { M3Text(subtitle, maxLines = SUPPORTING_LINES, overflow = TextOverflow.Ellipsis) },
        leading = { SettingIconChip(icon) },
        // A real switch on a phone, not the drawn pill below: it can be dragged as well as
        // tapped, and TalkBack announces it as "switch, on" without being told.
        trailing = { M3Switch(checked = checked, onCheckedChange = { toggle() }) },
    ) { focused ->
        // A chip for the same reason ActionRow has one: the two kinds of row are stacked in one
        // column, and without it their titles would start at two different x positions.
        SettingIconChip(icon, inverted = focused)
        Spacer(Modifier.size(16.dp))
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
                .heightIn(min = 56.dp)
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
            .heightIn(min = 64.dp)
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
 * and under it the choices as the pickers' radio groups (size, colour, background box and how
 * dark, edge, typeface, weight, position), then Back to the default look.
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
            ChoiceSection(s.subtitlesColour)
            com.tmplayer.player.SubtitleColour.entries.forEach { colour ->
                ChoiceLine(title = colour.label, selected = colour == style.colour) {
                    onChange(style.copy(colour = colour))
                }
            }
            ChoiceSection(s.settingsSubtitleBox)
            com.tmplayer.player.SubtitleBox.entries.forEach { level ->
                ChoiceLine(
                    title = level.label,
                    detail = when (level) {
                        com.tmplayer.player.SubtitleBox.Off -> s.settingsSubtitleBoxOff
                        com.tmplayer.player.SubtitleBox.Medium -> s.settingsSubtitleBoxOn
                        else -> null
                    },
                    selected = level == style.boxLevel,
                ) { onChange(style.copy(boxLevel = level)) }
            }
            // The edge only matters without a box; with one, the box is what separates the text.
            if (!style.box) {
                ChoiceSection(s.subtitlesEdge)
                com.tmplayer.player.SubtitleEdge.entries.forEach { edge ->
                    ChoiceLine(title = edge.label, selected = edge == style.edge) {
                        onChange(style.copy(edge = edge))
                    }
                }
            }
            ChoiceSection(s.subtitlesFace)
            com.tmplayer.player.SubtitleFace.entries.forEach { face ->
                ChoiceLine(title = face.label, selected = face == style.face) {
                    onChange(style.copy(face = face))
                }
            }
            ChoiceSection(s.subtitlesWeight)
            ChoiceLine(title = s.subtitlesWeightRegular, selected = !style.bold) { onChange(style.copy(bold = false)) }
            ChoiceLine(title = s.subtitlesWeightBold, selected = style.bold) { onChange(style.copy(bold = true)) }
            ChoiceSection(s.settingsSubtitlePosition)
            SubtitlePosition.entries.forEach { position ->
                ChoiceLine(title = position.label, selected = position == style.position) {
                    onChange(style.copy(position = position))
                }
            }
            // Last, and greyed out (still focusable) while the look is the default one.
            ChoiceLine(
                title = s.subtitlesDefaultLook,
                detail = s.subtitlesDefaultLookDetail,
                icon = TmIcons.Refresh,
                enabled = !style.isDefault,
            ) { onChange(SubtitleStyle()) }
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

/**
 * One notification's switch (CP76), drawn as every other switch in Settings. Turning it on asks
 * Android 13 and later for the notification permission there and then (and before that, says so
 * when the system has notifications off for the app); every change re-plans the background
 * checks, so a switch turned off stops its job.
 */
@Composable
private fun NoticeToggleRow(
    notice: Notice,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onBlocked: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val on by settings.noticeOn(notice).collectAsStateWithLifecycle(initialValue = notice.defaultOn)
    ToggleRow(
        title = title,
        subtitle = subtitle,
        icon = TmIcons.Bell,
        checked = on,
        modifier = modifier,
        onToggle = {
            val next = !on
            if (next) {
                com.tmplayer.ui.browse.askForNotifications(context)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU &&
                    !com.tmplayer.data.BackgroundChecks.canNotify(context)
                ) {
                    onBlocked()
                }
            }
            scope.launch {
                settings.setNoticeOn(notice, next)
                com.tmplayer.data.BackgroundChecks.schedule(context.applicationContext)
            }
        },
    )
}

/** How long Undo stands in for Back to the defaults. */
private const val APPEARANCE_UNDO_MS = 10_000L
