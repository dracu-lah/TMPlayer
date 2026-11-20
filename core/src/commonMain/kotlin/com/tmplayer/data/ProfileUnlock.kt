package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

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

    fun isUnlocked(id: String): Boolean = open.value == id

    /** The app's last window left the screen (not a rotation). */
    fun onBackground(now: Long = PinClock.now()) {
        leftAt = now
    }

    /** A window of the app is back: after more than [GRACE_MS] away the open profile locks. */
    fun onForeground(now: Long = PinClock.now()) {
        val left = leftAt
        leftAt = -1L
        // A clock behind the stamp started again (a reboot): the absence was long.
        if (left >= 0 && (now < left || now - left > GRACE_MS)) lock()
    }

    /** The open profile of [list] has a PIN that was not given in this process. */
    fun needsUnlock(list: AccountList, unlocked: String? = open.value): Boolean {
        val active = list.active ?: return false
        return active.pin != null && active.id != unlocked
    }

    /** Whether the open profile's content must stay off every surface outside the app now. */
    fun hiddenNow(): Boolean = Accounts.book?.list?.value?.let { needsUnlock(it) } ?: false

    /** [hiddenNow] as it changes: the widget, the TV's rows and the notifications follow it. */
    fun hidden(): Flow<Boolean> {
        val book = Accounts.book ?: return flowOf(false)
        return combine(book.list, open) { list, id -> needsUnlock(list, id) }.distinctUntilChanged()
    }

    /** For tests: nothing unlocked, nobody away. */
    internal fun resetForTest() {
        open.value = null
        leftAt = -1L
    }
}
