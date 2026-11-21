package com.tmplayer.desktop.os

import com.tmplayer.data.Accounts
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.platform.TransferNotifier
import com.tmplayer.platform.TransferNotifier.Kind
import com.tmplayer.platform.TransferNotifier.OpenTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What shows the open profile outside the desktop's window (HX88, invariant 6), and its one rule:
 * while a PIN profile is hidden ([ProfileUnlock.hiddenNow]: locked, or no window on screen) the
 * system's notifications and media controls carry the app's name and nothing of the profile.
 *
 * - Download and move progress ([ProfileGuardedNotifier], every transfer of the desktop goes
 *   through it): neutral while hidden, each one drawn again with its real title once shown.
 * - Download and move results: neutral while hidden (the app's name, no file to open).
 * - New video, episode and piece alerts ([profileNotice]): not posted while hidden.
 * - Low storage, a new version, the support ask: the device's, unchanged.
 * - The media controls (MPRIS on Linux, SMTC on Windows) through [mediaTitle] in the player host.
 * - On a switch or a sign out every transfer of the profile left comes off the system's list.
 *
 * Not blockable here, and said so in docs/agents/multi-account.md: the window's title is always
 * "TMPlayer" (nothing to hide), but the system's own pictures of the window (the taskbar or dock
 * preview, Alt+Tab, Mission Control, a screenshot) show whatever the window last drew, and a result
 * already posted stays in the system's history (Windows Action Center) until it is dismissed.
 * There is no tray icon, no recent items list and no presence (Discord or other) on the desktop.
 */
object ProfileOutside {
    /** The neutral words: the app's name, the same on every language (a name, not a sentence). */
    const val APP_NAME = "TMPlayer" // i18n-ok: the app's name

    /** The title the system's media controls show for [real]. */
    fun mediaTitle(real: String, hidden: Boolean): String = if (hidden) APP_NAME else real

    /** Whether a profile's alert may go out now. */
    fun alertsAllowed(hidden: Boolean): Boolean = !hidden

    /** The open profile is hidden now (a PIN profile locked, or no window on screen). */
    fun hiddenNow(): Boolean = runCatching { ProfileUnlock.hiddenNow() }.getOrDefault(true)

    /**
     * A profile's alert (a new video, an episode, a piece): dropped while the profile is hidden,
     * as the phone never even checks then. Device notices go to [target] directly.
     */
    internal fun profileNotice(
        target: DesktopNotices?,
        title: String,
        body: String?,
        onPress: (() -> Unit)?,
        buttons: List<NoticeButton> = emptyList(),
        hidden: Boolean = hiddenNow(),
    ) {
        if (!alertsAllowed(hidden)) return
        target?.notice(title, body, onPress, buttons)
    }

    @Volatile
    internal var guarded: ProfileGuardedNotifier? = null

    /** Every transfer of the open profile off the system's list: a sign out, a switch. */
    fun clearProfile() {
        guarded?.clearProfile()
    }

    /**
     * For the process: each time the open profile hides or shows the transfers are drawn again,
     * and on each switch the last profile's come down.
     */
    fun follow(scope: CoroutineScope, hidden: Flow<Boolean> = ProfileUnlock.hidden(), switches: Flow<Any?> = Accounts.activeFolder.drop(1)): Job =
        scope.launch {
            launch { hidden.collect { runCatching { guarded?.refresh() } } }
            launch { switches.collect { runCatching { clearProfile() } } }
        }
}

/**
 * The platform's notifier behind the profile rule ([ProfileOutside]): what it is given for a
 * transfer it posts with the real title only while the profile shows, the app's name otherwise.
 */
internal class ProfileGuardedNotifier(
    private val inner: TransferNotifier,
    private val hidden: () -> Boolean = ProfileOutside::hiddenNow,
) : TransferNotifier, DesktopNotices, DockProgress {

    private class Ongoing(val kind: Kind, val title: String, var neutral: Boolean, var done: Long = 0, var total: Long? = null)

    private val ongoing = LinkedHashMap<Long, Ongoing>()

    override val capabilities: Set<TransferNotifier.Capability> get() = inner.capabilities

    override fun begin(id: Long, kind: Kind, title: String) {
        val neutral = hidden()
        synchronized(ongoing) { ongoing[id] = Ongoing(kind, title, neutral) }
        inner.begin(id, kind, if (neutral) ProfileOutside.APP_NAME else title)
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) {
        synchronized(ongoing) {
            ongoing[id]?.let {
                it.done = done
                it.total = total
            }
        }
        inner.progress(id, done, total, bytesPerSecond)
    }

    override fun complete(id: Long, title: String, body: String, open: OpenTarget?) {
        synchronized(ongoing) { ongoing.remove(id) }
        if (hidden()) inner.complete(id, ProfileOutside.APP_NAME, "", OpenTarget.DownloadsScreen) else inner.complete(id, title, body, open)
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
        synchronized(ongoing) { ongoing.remove(id) }
        if (hidden()) inner.fail(id, ProfileOutside.APP_NAME, "", retryable) else inner.fail(id, title, reason, retryable)
    }

    override fun cancel(id: Long) {
        synchronized(ongoing) { ongoing.remove(id) }
        inner.cancel(id)
    }

    /** The device's notices (low storage, updates, the support ask): never changed. */
    override fun notice(title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) {
        (inner as? DesktopNotices)?.notice(title, body, onPress, buttons)
    }

    override fun dockProgress(fraction: Float?) {
        (inner as? DockProgress)?.dockProgress(fraction)
    }

    /** Each transfer still up drawn again, neutral or real, when the profile hid or showed. */
    fun refresh() {
        val neutral = hidden()
        val redraw = synchronized(ongoing) {
            ongoing.filter { it.value.neutral != neutral }.onEach { it.value.neutral = neutral }.toList()
        }
        for ((id, entry) in redraw) {
            inner.begin(id, entry.kind, if (neutral) ProfileOutside.APP_NAME else entry.title)
            if (entry.done > 0) inner.progress(id, entry.done, entry.total, null)
        }
    }

    /** Every transfer of the profile off the system's list. */
    fun clearProfile() {
        val ids = synchronized(ongoing) { ongoing.keys.toList().also { ongoing.clear() } }
        ids.forEach { inner.cancel(it) }
    }
}
