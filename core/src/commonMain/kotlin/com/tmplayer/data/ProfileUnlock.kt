package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Whether the open profile's PIN was given (HX88), for the process, in memory only: never saved,
 * so a process that died asks again. Only the open profile is ever unlocked; a switch, a sign out,
 * a remove or an add locks it ([lock] through [Accounts.onSwitch]), so opening any profile with a
 * PIN asks for it, every time. The app lock locking the app locks it too ([AppLockSession.onLock]),
 * and so does the app staying in the background longer than [GRACE_MS] ([onBackground] and
 * [onForeground], with or without an app lock), timed on [PinClock], which only moves forward.
 *
 * What shows one profile's content outside the app (the widget, the TV's home channels and Play
 * next row, notifications) asks [hidden] first: a PIN locked profile shows none of it.
 */
object ProfileUnlock {
    /** A short absence (a rotation, a permission dialog, a glance at another app) asks nothing again. */
    const val GRACE_MS = 60_000L

    private val open = MutableStateFlow<String?>(null)

    /** The profile whose PIN was given, while it is the open one; null for none. */
    val unlockedId: StateFlow<String?> = open.asStateFlow()

    @Volatile
    private var leftAt: Long = -1L

    private val lockListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    init {
        Accounts.onSwitch { lock() }
        AppLockSession.onLock { lock() }
    }

    /** Touches the object, so it hears switches and the app lock from the start. */
    fun install() = Unit

    /** [id]'s PIN was given at its pad, with [id] the open profile. */
    fun unlock(id: String) {
        open.value = id
    }

    /** The open profile asks its PIN again; what shows its content outside the app hides it. */
    fun lock() {
        val was = open.value
        open.value = null
        if (was != null) for (each in lockListeners) runCatching(each)
    }

    /** Runs [block] each time an unlocked profile locks again: to take its content off the launcher. */
    fun onLock(block: () -> Unit) {
        lockListeners += block
    }

    fun isUnlocked(id: String, now: Long = PinClock.now()): Boolean = open.value == id && !awayTooLong(now)

    @Volatile
    private var timer: Job? = null

    /**
     * The app's last window left the screen (not a rotation). The lock itself comes [lockAfterMs]
     * later, so the widget and the launcher hear it while the app is still away; and whoever reads
     * the unlock before then ([hiddenNow], [isUnlocked]) counts the absence there and then.
     */
    fun onBackground(now: Long = PinClock.now(), lockAfterMs: Long = GRACE_MS + 1) {
        leftAt = now
        // Outside the app a PIN profile's content goes at once ([hidden]): a process frozen or
        // killed in the background never runs the timer below, and its cards must not stay.
        away.value = true
        timer?.cancel()
        timer = Background.scope.launch {
            delay(lockAfterMs)
            if (leftAt >= 0) lock()
        }
    }

    /** A window of the app is back: after more than [GRACE_MS] away the open profile locks. */
    fun onForeground(now: Long = PinClock.now()) {
        timer?.cancel()
        timer = null
        val tooLong = awayTooLong(now)
        leftAt = -1L
        away.value = false
        if (tooLong) lock()
    }

    /** Whether no window of the app is on screen now. */
    private val away = MutableStateFlow(false)

    /** Away longer than [GRACE_MS], or a clock behind the stamp (a reboot): the absence was long. */
    private fun awayTooLong(now: Long): Boolean {
        val left = leftAt
        return left >= 0 && (now < left || now - left > GRACE_MS)
    }

    /** The open profile of [list] has a PIN that was not given in this process. */
    fun needsUnlock(list: AccountList, unlocked: String? = open.value): Boolean {
        val active = list.active ?: return false
        return active.pin != null && active.id != unlocked
    }

    /**
     * Inside the app: the open profile asks its PIN before anything of it is drawn, counting an
     * absence past [GRACE_MS] there and then, before any timer has run (a player resumed late).
     */
    fun lockedNow(now: Long = PinClock.now()): Boolean {
        val list = Accounts.book?.list?.value ?: return false
        return needsUnlock(list, if (awayTooLong(now)) null else open.value)
    }

    /**
     * Outside the app (the widget, the TV's rows, notifications): a PIN profile's content shows
     * only while its PIN was given and the app is on screen; the moment the app leaves the screen
     * it goes, and comes back once the app is open and the profile unlocked again.
     */
    fun hiddenNow(now: Long = PinClock.now()): Boolean {
        val list = Accounts.book?.list?.value ?: return false
        if (list.active?.pin == null) return false
        return away.value || lockedNow(now)
    }

    /** [hiddenNow] as it changes, from its first value. */
    fun hidden(): Flow<Boolean> {
        val book = Accounts.book ?: return flowOf(false)
        return combine(book.list, open, away) { list, id, gone ->
            list.active?.pin != null && (gone || needsUnlock(list, if (awayTooLong(PinClock.now())) null else id))
        }.distinctUntilChanged()
    }

    /** Whether the open profile has a PIN, as it changes (a PIN set or taken off, a switch), from its first value. */
    fun activeHasPin(): Flow<Boolean> {
        val book = Accounts.book ?: return flowOf(Accounts.active.pin != null)
        return book.list.map { it.active?.pin != null }.distinctUntilChanged()
    }

    /** For tests: nothing unlocked, nobody away. */
    internal fun resetForTest() {
        timer?.cancel()
        open.value = null
        leftAt = -1L
        away.value = false
    }
}

/**
 * The one rule every writer to a surface outside the app obeys (HX88): the launcher's Play next
 * row and channels, notifications with titles. Nothing of a profile is written while it is hidden
 * ([ProfileUnlock.hiddenNow]) or once its turn is over ([stamp] no longer [Accounts.stamp]);
 * taking an entry away is always allowed.
 */
object OutsideWrites {
    fun mayWrite(hidden: Boolean, stamp: AccountStamp?, current: AccountStamp, removing: Boolean = false): Boolean =
        removing || (!hidden && (stamp == null || stamp == current))

    /** [mayWrite] for the profile and the moment now. */
    fun mayWriteNow(stamp: AccountStamp? = null, removing: Boolean = false): Boolean =
        mayWrite(ProfileUnlock.hiddenNow(), stamp, Accounts.stamp, removing)

    /**
     * Whether a window showing the open profile keeps out of the recents thumbnail (and, below
     * Android 13, of screenshots): only while the open profile has a PIN, never otherwise.
     */
    fun secureWindow(openHasPin: Boolean): Boolean = openHasPin

    /**
     * What a cast names (Google Cast's own notification, lock screen controls and Bluetooth, and
     * the TV's screen): with a PIN profile open, the app's name only, from the load on, since the
     * framework cannot change a loaded item's titles while it plays. Real titles otherwise.
     */
    fun castTitles(title: String, subtitle: String, openHasPin: Boolean, appName: String): Pair<String, String> =
        if (openHasPin) appName to "" else title to subtitle

    /** Whether the open profile has a PIN, as it changes. */
    fun openHasPin(): Flow<Boolean> {
        val book = Accounts.book ?: return flowOf(false)
        return book.list.map { it.active?.pin != null }.distinctUntilChanged()
    }
}

/**
 * A Telegram session bound to the profile a job works for (HX88). [Td.sessionFor] is this over
 * the live client; apart from Td so a test drives it with fake sessions and a fake switch.
 */
object OwnedSessions {
    /** [owner] is the open profile's turn and no switch is under way. */
    fun mayAct(owner: AccountStamp, current: AccountStamp = Accounts.stamp, switching: Boolean = Accounts.book?.list?.value?.switchingTo != null): Boolean =
        owner == current && !switching

    /**
     * The session [next] gives, kept only when it is [owner]'s ([ownerOf]) and [owner] may still
     * act; null when it times out, or when the session that came up is another profile's (the
     * client of the last profile closed slowly and the next one's came up while this waited).
     */
    suspend fun <S : Any> await(
        owner: AccountStamp,
        mayAct: () -> Boolean,
        ownerOf: (S) -> AccountStamp,
        timeoutMs: Long,
        next: suspend () -> S,
    ): S? {
        if (!mayAct()) return null
        val session = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { next() } ?: return null
        return session.takeIf { ownerOf(it) == owner && mayAct() }
    }

    /** Thrown before a Telegram write of a job whose profile is no longer open: the job stops there. */
    class ProfileGone : IllegalStateException("another profile is open")

    /** Before each Telegram write and store write of an owned job: stops it when its profile is gone. */
    suspend fun ensure() {
        val owner = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp ?: return
        if (!mayAct(owner)) throw ProfileGone()
    }
}
