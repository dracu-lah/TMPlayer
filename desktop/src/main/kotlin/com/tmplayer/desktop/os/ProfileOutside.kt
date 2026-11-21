package com.tmplayer.desktop.os

import com.tmplayer.data.AccountStamp
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

    /**
     * The one rule (as Android's ProfileNotifications): the real words only for the profile that
     * made them ([owner]) while it is the open one ([current]) and shown; otherwise neutral words
     * or nothing.
     */
    fun mayShow(owner: AccountStamp?, current: AccountStamp, hidden: Boolean): Boolean =
        !hidden && (owner == null || owner == current)

    /**
     * The title the system's media controls show for [real], played under [owner] (null: the
     * open profile's).
     */
    fun mediaTitle(real: String, hidden: Boolean, owner: AccountStamp? = null, current: AccountStamp = Accounts.stamp): String =
        if (mayShow(owner, current, hidden)) real else APP_NAME

    /** Whether a profile's alert may go out now. */
    fun alertsAllowed(hidden: Boolean): Boolean = !hidden

    /** The open profile is hidden now (a PIN profile locked, or no window on screen). */
    fun hiddenNow(): Boolean = runCatching { ProfileUnlock.hiddenNow() }.getOrDefault(true)

    /**
     * A profile's alert (a new video, an episode, a piece), made for [owner]: posted only while
     * [owner] is still the open profile and shown, read again at the moment of posting (an alert
     * built before a switch or a lock is dropped, never posted for the next profile). Device
     * notices go to [target] directly.
     */
    internal fun profileNotice(
        target: DesktopNotices?,
        owner: AccountStamp,
        title: String,
        body: String?,
        onPress: (() -> Unit)?,
        buttons: List<NoticeButton> = emptyList(),
        hidden: Boolean = hiddenNow(),
        current: AccountStamp = Accounts.stamp,
    ) {
        if (!mayShow(owner, current, hidden)) return
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
    private val current: () -> AccountStamp = { Accounts.stamp },
) : TransferNotifier, DesktopNotices, DockProgress {

    /** A transfer up on the system's list: the profile it was begun for ([owner]) and what it said. */
    private class Ongoing(val owner: AccountStamp, val kind: Kind, val title: String, var neutral: Boolean, var done: Long = 0, var total: Long? = null)

    private val ongoing = LinkedHashMap<Long, Ongoing>()

    /** Ids taken down with their profile ([clearProfile]): a late update of one posts nothing. */
    private val gone = LinkedHashSet<Long>()

    override val capabilities: Set<TransferNotifier.Capability> get() = inner.capabilities

    /** Its owner may show it now: still the open profile, and shown. */
    private fun real(owner: AccountStamp): Boolean = ProfileOutside.mayShow(owner, current(), hidden())

    override fun begin(id: Long, kind: Kind, title: String) {
        val owner = current()
        val neutral = !real(owner)
        synchronized(ongoing) {
            gone -= id
            ongoing[id] = Ongoing(owner, kind, title, neutral)
        }
        inner.begin(id, kind, if (neutral) ProfileOutside.APP_NAME else title)
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) {
        val forward = synchronized(ongoing) {
            val entry = ongoing[id]
            entry?.let {
                it.done = done
                it.total = total
            }
            // One taken down with its profile, or one of a profile no longer open: nothing.
            id !in gone && (entry == null || entry.owner == current())
        }
        if (forward) inner.progress(id, done, total, bytesPerSecond)
    }

    /**
     * Whose a result is: the owner its [begin] recorded; for one never begun (a download that
     * failed before it started, its rows still the open profile's), the open profile's. Null for
     * one taken down with its profile: nothing is posted.
     */
    private fun ownerOfResult(id: Long): AccountStamp? = synchronized(ongoing) {
        if (id in gone) null else ongoing.remove(id)?.owner ?: current()
    }

    override fun complete(id: Long, title: String, body: String, open: OpenTarget?) {
        val owner = ownerOfResult(id) ?: return
        if (real(owner)) inner.complete(id, title, body, open) else inner.complete(id, ProfileOutside.APP_NAME, "", OpenTarget.DownloadsScreen)
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
        val owner = ownerOfResult(id) ?: return
        if (real(owner)) inner.fail(id, title, reason, retryable) else inner.fail(id, ProfileOutside.APP_NAME, "", retryable)
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
        val redraw = synchronized(ongoing) {
            ongoing.mapNotNull { (id, entry) ->
                val neutral = !real(entry.owner)
                if (entry.neutral == neutral) null else id to entry.also { it.neutral = neutral }
            }
        }
        for ((id, entry) in redraw) {
            inner.begin(id, entry.kind, if (entry.neutral) ProfileOutside.APP_NAME else entry.title)
            if (entry.done > 0 && entry.owner == current()) inner.progress(id, entry.done, entry.total, null)
        }
    }

    /** Every transfer of the profile off the system's list; a late update of one posts nothing. */
    fun clearProfile() {
        val ids = synchronized(ongoing) {
            ongoing.keys.toList().also {
                gone += it
                ongoing.clear()
                // Bounded: the oldest go first; a transfer's id is begun again before it is reused.
                while (gone.size > MAX_GONE) gone.remove(gone.first())
            }
        }
        ids.forEach { inner.cancel(it) }
    }

    private companion object {
        const val MAX_GONE = 512
    }


}
