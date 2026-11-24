package com.tmplayer.desktop.profiles

import com.tmplayer.data.AccountSwitcher
import com.tmplayer.data.Accounts
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.BeforeSwitch
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Profile
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SwitchPause
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import com.tmplayer.ui.lock.PinChecker
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilePlatform
import com.tmplayer.ui.lock.SettingsPinChecker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The profiles on the desktop (HX88, docs/agents/multi-account.md): one [ProfileFlow] for the
 * process, over the process's [AccountSwitcher], so the picker's state outlives a window that is
 * built again and is gone with the process (a PIN is asked again after that). Null in a build or a
 * test that never opened the accounts: then nothing of the profiles is drawn.
 */
object DesktopProfiles {
    @Volatile
    private var flow: ProfileFlow? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The flow's short messages ("Signed out of Asha"), for the window's own toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The process's flow, made on first use over the live services, or null before [Accounts.open]. */
    fun flow(): ProfileFlow? = flow ?: synchronized(this) {
        flow ?: AccountSwitcher.current()?.let { switcher ->
            ProfileFlow(switcher, DesktopProfilePlatform.live(), Background.scope).also { flow = it }
        }
    }

    internal fun say(text: String) {
        _messages.tryEmit(text)
    }

    /** For tests: a flow of their own, or none. */
    internal fun replaceForTest(next: ProfileFlow?) {
        synchronized(this) { flow = next }
    }
}

/**
 * What the desktop gives [ProfileFlow]: the player and the download runner of the open profile,
 * and its sign out. Built over the live services by [live]; a test hands its own.
 */
internal class DesktopProfilePlatform(
    private val settings: () -> SettingsStore,
    private val watched: () -> WatchedStore?,
    private val bookmarks: () -> BookmarkStore?,
    private val runner: () -> DownloadRunner,
    private val settleMoves: suspend () -> Unit = {},
    private val playingNow: () -> Boolean = { BeforeSwitch.playerCount > 0 },
    private val forget: suspend (SettingsStore, WatchedStore?, BookmarkStore?, Boolean, Boolean) -> Unit = ::desktopForgetProfile,
    private val clearOutside: () -> Unit = { com.tmplayer.desktop.os.ProfileOutside.clearProfile() },
    private val toast: (String) -> Unit = DesktopProfiles::say,
) : ProfilePlatform {

    /** A player registers its save with [BeforeSwitch] while it is open; a cast counts too. */
    override fun playing(): Boolean = playingNow()

    override fun downloading(): Boolean = OfflineDownloads.active.value.values.any { it.busy }

    /** The downloads this switch's wait paused, in memory only: a Cancel puts them back ([SwitchPause]). */
    @Volatile
    internal var held: SwitchPause.Held? = null

    /**
     * The player saves its place and closes ([BeforeSwitch], registered by the player host), the
     * open profile's downloads pause, their queue written while its stores are still the open
     * ones, and a move into Downloads under way is let finish, all under the one deadline.
     */
    override suspend fun beforeSwitch(onWaiting: (List<String>) -> Unit) {
        val stamp = Accounts.stamp
        val rows = OfflineDownloads.rowsGeneration
        val now = OfflineDownloads.active.value.values.filter { it.busy }
            .map { SwitchPause.Paused(it.fileId, it.request.chatId, it.request.messageId) }
        held = SwitchPause.hold(held, stamp, rows, now)
        if (downloading()) runCatching { OfflineDownloads.pauseAll(runner()) }
        val pausing = downloading()
        BeforeSwitch.runAll(onWaiting = { what -> onWaiting(if (pausing && downloading()) what + L.profilesWaitingDownloads else what) })
        withTimeoutOrNull(PAUSE_WAIT_MS) {
            while (downloading()) delay(50)
        }
        // A finished download on its way into the Downloads folder: let it land as this profile's.
        withTimeoutOrNull(MOVE_WAIT_MS) { runCatching { settleMoves() } }
    }

    override suspend fun switchCancelled() {
        val was = held
        held = null
        // Only into the profile and the rows they belong to, while they are still the open ones.
        val rowsNow = OfflineDownloads.active.value.mapValues { (_, row) -> row.request.chatId to row.request.messageId }
        val ids = SwitchPause.toResume(was, Accounts.stamp, OfflineDownloads.rowsGeneration, rowsNow)
        for (id in ids) runCatching { OfflineDownloads.resume(runner(), id) }
    }

    override suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean) {
        // A sign out discards what its wait paused: the next sign in is a new database.
        if (held?.stamp?.folder == profile.folder) held = null
        // Its results and alerts leave the system's notifications with it (HX88).
        runCatching { clearOutside() }
        forget(settings(), watched(), bookmarks(), deleteDownloads, last)
    }

    override val appLock: Flow<AppLockSettings?> get() = settings().appLock

    override fun appLockChecker(): PinChecker = SettingsPinChecker(settings())

    /**
     * Not read at once (no disk read on the window's first frame): the flow is fail closed until
     * [appLock]'s first value, a few milliseconds in. Only the picker (two profiles or more) and
     * the guarded actions wait on it; one profile with no PIN draws its shell at once.
     */
    override fun appLockNow(): AppLockSettings? = null

    override fun say(text: String) = toast(text)

    companion object {
        const val PAUSE_WAIT_MS = 3_000L
        const val MOVE_WAIT_MS = 10_000L

        fun live() = DesktopProfilePlatform(
            settings = { com.tmplayer.desktop.DesktopServices.settings },
            watched = { com.tmplayer.desktop.DesktopServices.watched },
            bookmarks = { com.tmplayer.desktop.DesktopServices.bookmarks },
            runner = { com.tmplayer.desktop.DesktopServices.downloads },
            settleMoves = { com.tmplayer.desktop.DesktopServices.downloads.settleMoves() },
        )
    }
}

/**
 * Signing out of one profile on the desktop (HX88), run by `AccountSwitcher.signOutProfile` with
 * that profile open, so every store below is its own. The last profile goes as the desktop's sign
 * out always did ([com.tmplayer.desktop.ui.signOut]: the device's crash report consent and online
 * subtitle picks too). With others left only the account's own data goes: its keys
 * ([SettingsStore.clearAccountData]: the theme, the player, the app lock and the kids lock stay,
 * as they are the device's), its watched list, Watch later, bookmarks, TDLib's media cache, and its
 * downloads when [deleteDownloads]. TDLib's database goes with the profile's folders after the log
 * out. Either way the next sign in is a new database ([Td.renewDatabaseMarker]).
 */
internal suspend fun desktopForgetProfile(
    settings: SettingsStore,
    watched: WatchedStore?,
    bookmarks: BookmarkStore?,
    deleteDownloads: Boolean,
    last: Boolean,
) {
    runCatching { Td.renewDatabaseMarker() }
    if (last) {
        com.tmplayer.desktop.ui.signOut(settings, deleteDownloads, watched, bookmarks)
        return
    }
    val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    if (deleteDownloads) {
        withContext(Dispatchers.IO) { downloads.forEach { runCatching { com.tmplayer.desktop.DownloadIndex.delete(settings, it) } } }
    }
    runCatching { withTimeoutOrNull(CLEAR_CACHE_MS) { Td.clearMediaCache() } }
    runCatching { settings.clearAccountData(keepDownloads = !deleteDownloads) }
    watched?.let {
        runCatching { it.clear() }
        runCatching { it.watchLater.clear() }
    }
    bookmarks?.let { runCatching { it.clear() } }
    runCatching { com.tmplayer.data.SavedMessagesBackup.forgetFiles() }
    Logger.i("DesktopProfiles", "signed out of one profile; ${downloads.size} download record(s) ${if (deleteDownloads) "deleted" else "kept"}")
}

private const val CLEAR_CACHE_MS = 5_000L

/**
 * The desktop's windows on screen or not (HX88): [ProfileUnlock.onBackground] when the last one
 * is minimised or hidden, [ProfileUnlock.onForeground] when one is back. A window that only lost
 * the focus to another app is still on screen and counts as shown. Back after more than
 * [ProfileUnlock.GRACE_MS] away, the open profile asks its PIN again.
 */
class WindowPresence(
    private val now: () -> Long = { com.tmplayer.data.PinClock.now() },
    private val background: (Long) -> Unit = { ProfileUnlock.onBackground(it) },
    private val foreground: (Long) -> Unit = { ProfileUnlock.onForeground(it) },
) {
    private val shown = java.util.Collections.synchronizedSet(HashSet<Any>())
    private var away = false

    /** The whole app hidden (macOS Hide, Cmd+H): away, whatever each window says of itself. */
    private var appHidden = false

    /** [window] is on screen ([visible] and not minimised) or not. */
    fun update(window: Any, visible: Boolean) = settle { if (visible) shown += window else shown -= window }

    /**
     * The app as a whole hidden or shown again (HX88, review P4): macOS's Hide (Cmd+H) leaves each
     * window showing as far as AWT's window events say, so it is heard on its own.
     */
    fun appHidden(hidden: Boolean) = settle { appHidden = hidden }

    private fun settle(edit: () -> Unit) {
        val change = synchronized(this) {
            edit()
            val nowAway = appHidden || shown.isEmpty()
            if (nowAway == away) null else nowAway.also { away = it }
        } ?: return
        if (change) background(now()) else foreground(now())
    }

    /** Follows [window]'s minimise, restore, hide and show; the returned call stops following it. */
    fun follow(window: java.awt.Frame): () -> Unit {
        fun visible() = window.isShowing && (window.extendedState and java.awt.Frame.ICONIFIED) == 0
        val states = java.awt.event.WindowStateListener { update(window, visible()) }
        val events = object : java.awt.event.WindowAdapter() {
            override fun windowIconified(e: java.awt.event.WindowEvent) = update(window, false)
            override fun windowDeiconified(e: java.awt.event.WindowEvent) = update(window, true)
            override fun windowOpened(e: java.awt.event.WindowEvent) = update(window, visible())
        }
        val components = object : java.awt.event.ComponentAdapter() {
            override fun componentHidden(e: java.awt.event.ComponentEvent) = update(window, false)
            override fun componentShown(e: java.awt.event.ComponentEvent) = update(window, visible())
        }
        window.addWindowStateListener(states)
        window.addWindowListener(events)
        window.addComponentListener(components)
        val hides = followAppHidden()
        if (window.isShowing) update(window, visible())
        return {
            window.removeWindowStateListener(states)
            window.removeWindowListener(events)
            window.removeComponentListener(components)
            hides()
        }
    }

    /** macOS's Hide and Show, where the system tells of them (java.awt.desktop); nothing elsewhere. */
    private fun followAppHidden(): () -> Unit = runCatching {
        if (!java.awt.Desktop.isDesktopSupported()) return@runCatching null
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_HIDDEN)) return@runCatching null
        val listener = object : java.awt.desktop.AppHiddenListener {
            override fun appHidden(e: java.awt.desktop.AppHiddenEvent) = appHidden(true)
            override fun appUnhidden(e: java.awt.desktop.AppHiddenEvent) = appHidden(false)
        }
        desktop.addAppEventListener(listener)
        val undo: () -> Unit = { runCatching { desktop.removeAppEventListener(listener) } }
        undo
    }.getOrNull() ?: {}

    companion object {
        /** The process's, for the main window. */
        val live = WindowPresence()
    }
}

/**
 * [com.tmplayer.ui.lock.ProfilesGate] as the desktop draws it: the shared gate, and the arrow keys
 * walking Who's watching? and its panels as Tab does (a desktop has no D-pad, and Compose moves
 * focus with the arrows only on one). Taken only when nothing focused wanted the key, so a text
 * field (Rename) keeps its caret; while the shell shows, the arrows are the shell's as before.
 */
@androidx.compose.runtime.Composable
fun DesktopProfilesGate(
    flow: ProfileFlow?,
    signInStep: Boolean,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shellShown = com.tmplayer.ui.lock.profileShellShown(flow)
    val overShell = flow != null && flow.stage != ProfileFlow.Stage.Picker
    val arrows = !shellShown || overShell
    Box(Modifier.fillMaxSize().onKeyEvent { event -> arrows && arrowMove(event)?.let { focus.moveFocus(it) } == true }) {
        com.tmplayer.ui.lock.ProfilesGate(flow, signInStep, sendLogs = { com.tmplayer.desktop.ui.SendLogsActions() }, content = content)
    }
}

/** The focus move an arrow key with no modifier asks for; null for any other key. */
internal fun arrowMove(event: androidx.compose.ui.input.key.KeyEvent): androidx.compose.ui.focus.FocusDirection? {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return null
    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) return null
    return when (event.key) {
        androidx.compose.ui.input.key.Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
        androidx.compose.ui.input.key.Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
        androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
        androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
        else -> null
    }
}
