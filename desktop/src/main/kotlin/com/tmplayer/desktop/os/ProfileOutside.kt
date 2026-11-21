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
 * - Download and move results: neutral while hidden (the app's name, no file to open). A real one
 *   is kept by the profile it was posted for and taken off the system's list when that profile
 *   hides, is left or signs out ([KeyedResults]); a press on one whose profile is not the open,
 *   shown one opens nothing.
 * - New video, episode and piece alerts ([profileNotice]): not posted while hidden, taken down the
 *   same way, and their press does nothing once their profile is not the open, shown one.
 * - Low storage, a new version, the support ask: the device's, unchanged.
 * - The media controls (MPRIS on Linux, SMTC on Windows) through [mediaTitle] in the player host.
 * - On a switch or a sign out every transfer of the profile left comes off the system's list.
 *
 * Not blockable here, and said so in docs/agents/multi-account.md: the window's title is always
 * "TMPlayer" (nothing to hide), but the system's own pictures of the window (the taskbar or dock
 * preview, Alt+Tab, Mission Control, a screenshot) show whatever the window last drew, and a
 * notify-send fallback (no session bus) cannot take a result down. There is no tray icon, no recent
 * items list and no presence (Discord or other) on the desktop, and macOS posts no notifications.
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

    private val caller = ThreadLocal<AccountStamp?>()

    /**
     * Runs [block], a call to the notifier, as the work of [owner] (HX88, review P5): a transfer
     * begun in it, and a result never begun, belong to [owner], not to whichever profile is open by
     * then. The notifiers pass every call on, on the caller's thread, so the owner travels with it.
     */
    fun <T> asOwner(owner: AccountStamp?, block: () -> T): T {
        val before = caller.get()
        caller.set(owner)
        try {
            return block()
        } finally {
            caller.set(before)
        }
    }

    /** The owner [asOwner] is running a call for on this thread, or null. */
    internal fun callerOwner(): AccountStamp? = caller.get()

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
        if (target is ProfileGuardedNotifier) target.profileNotice(owner, title, body, onPress, buttons) else target?.notice(title, body, onPress, buttons)
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
 * A platform notifier whose results and notices can be taken down later by a key, and whose
 * presses ask first (HX88, review P2): Linux closes them by the server's id (CloseNotification),
 * Windows takes them out of the Action Center by their tag (History.Remove).
 */
internal interface KeyedResults {
    /** A finished ([failed] false) or failed transfer's result, kept under [key]; [mayOpen] is asked at its press. */
    fun result(key: Long, id: Long, title: String, body: String, open: OpenTarget?, failed: Boolean, mayOpen: () -> Boolean)

    /** A profile's alert, kept under [key]; its presses are the caller's, already guarded. */
    fun keyedNotice(key: Long, title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>)

    /** Takes what was posted under [key] off the system's list, if it is still there. */
    fun dismiss(key: Long)
}

/**
 * The platform's notifier behind the profile rule ([ProfileOutside]): what it is given for a
 * transfer it posts with the real title only while the profile shows, the app's name otherwise.
 */
internal class ProfileGuardedNotifier(
    private val inner: TransferNotifier,
    private val hidden: () -> Boolean = ProfileOutside::hiddenNow,
    private val current: () -> AccountStamp = { Accounts.stamp },
    /** The owner of the call in hand ([ProfileOutside.asOwner]), or null for the open profile. */
    private val callerOwner: () -> AccountStamp? = ProfileOutside::callerOwner,
) : TransferNotifier, DesktopNotices, DockProgress {

    /** A transfer up on the system's list: the profile it was begun for ([owner]) and what it said. */
    private class Ongoing(val owner: AccountStamp, val kind: Kind, val title: String, var neutral: Boolean, var done: Long = 0, var total: Long? = null)

    private val ongoing = LinkedHashMap<Long, Ongoing>()

    /** Ids taken down with their profile ([clearProfile]): a late update of one posts nothing. */
    private val gone = LinkedHashSet<Long>()

    /** Real results and alerts still on the system's list, by their key, and whose they are. */
    private val posted = LinkedHashMap<Long, AccountStamp>()
    private var nextNotice = NOTICE_KEYS

    override val capabilities: Set<TransferNotifier.Capability> get() = inner.capabilities

    /** Its owner may show it now: still the open profile, and shown. */
    private fun real(owner: AccountStamp): Boolean = ProfileOutside.mayShow(owner, current(), hidden())

    override fun begin(id: Long, kind: Kind, title: String) {
        val owner = callerOwner() ?: current()
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
     * failed before it started), the owner of the call ([ProfileOutside.asOwner]), else the open
     * profile's. Null for one taken down with its profile: nothing is posted.
     */
    private fun ownerOfResult(id: Long): AccountStamp? = synchronized(ongoing) {
        if (id in gone) null else ongoing.remove(id)?.owner ?: callerOwner() ?: current()
    }

    /** Keeps [key] as [owner]'s, bounded: the oldest go first. */
    private fun track(key: Long, owner: AccountStamp) = synchronized(ongoing) {
        posted.remove(key)
        posted[key] = owner
        while (posted.size > MAX_GONE) posted.remove(posted.keys.first())
    }

    override fun complete(id: Long, title: String, body: String, open: OpenTarget?) {
        val owner = ownerOfResult(id) ?: return
        if (!real(owner)) {
            inner.complete(id, ProfileOutside.APP_NAME, "", OpenTarget.DownloadsScreen)
            return
        }
        val keyed = inner as? KeyedResults ?: return inner.complete(id, title, body, open)
        track(id, owner)
        keyed.result(id, id, title, body, open, failed = false, mayOpen = { real(owner) })
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
        val owner = ownerOfResult(id) ?: return
        if (!real(owner)) {
            inner.fail(id, ProfileOutside.APP_NAME, "", retryable)
            return
        }
        val keyed = inner as? KeyedResults ?: return inner.fail(id, title, reason, retryable)
        track(id, owner)
        keyed.result(id, id, title, reason, null, failed = true, mayOpen = { real(owner) })
    }

    /**
     * A profile's alert for [owner], posted only while it may show ([ProfileOutside.profileNotice]
     * asked), kept so it comes down with its profile, its presses doing nothing once [owner] is not
     * the open, shown profile (a stale alert of a locked or left profile).
     */
    fun profileNotice(owner: AccountStamp, title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) {
        if (!real(owner)) return
        val press = onPress?.let { action -> { if (real(owner)) action() } }
        val guarded = buttons.map { button -> NoticeButton(button.label) { if (real(owner)) button.onPress() } }
        val keyed = inner as? KeyedResults
        if (keyed == null) {
            (inner as? DesktopNotices)?.notice(title, body, press, guarded)
            return
        }
        val key = synchronized(ongoing) { nextNotice++ }
        track(key, owner)
        keyed.keyedNotice(key, title, body, press, guarded)
    }

    /** Takes down every real result and alert whose profile may not show it now (all of them with [all]). */
    private fun dismissPosted(all: Boolean) {
        val keyed = inner as? KeyedResults ?: return
        val doomed = synchronized(ongoing) {
            posted.entries.filter { all || !real(it.value) }.map { it.key }.onEach { posted.remove(it) }
        }
        doomed.forEach { keyed.dismiss(it) }
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
        // Hidden now (locked, or no window on screen): its results and alerts leave the system's list.
        dismissPosted(all = false)
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
        // Its results and alerts too: a switch, a sign out (Android takes them down the same way).
        dismissPosted(all = true)
    }

    private companion object {
        const val MAX_GONE = 512

        /** The keys of alerts, apart from every transfer id. */
        const val NOTICE_KEYS = 1L shl 50
    }


}
