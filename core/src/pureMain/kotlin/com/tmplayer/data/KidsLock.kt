package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import com.tmplayer.platform.Background

/**
 * The kids lock (CP57): while it is on, browsing lists only the chats the viewer allowed.
 *
 * [allowed] is null when the lock is off, so every caller can ask one question, [allows], and the
 * answer for an unlocked app is always yes. An empty set is a lock with nothing chosen yet, which
 * shows no chats at all rather than all of them: a lock that fails open is not a lock.
 */
object KidsLock {

    /** Whether a chat may be shown under [allowed] (null: the lock is off). */
    fun allows(allowed: Set<Long>?, chatId: Long): Boolean = allowed == null || chatId in allowed

    /** [items] without those from chats the lock leaves out, in their order. */
    fun <T> filter(items: List<T>, allowed: Set<Long>?, chatId: (T) -> Long): List<T> =
        if (allowed == null) items else items.filter { chatId(it) in allowed }

    /** The chat list itself. */
    fun chats(chats: List<ChatSummary>, allowed: Set<Long>?): List<ChatSummary> = filter(chats, allowed) { it.id }

    /** Videos, wherever they came from: Home's rows, search across chats. */
    fun media(items: List<MediaItem>, allowed: Set<Long>?): List<MediaItem> = filter(items, allowed) { it.chatId }

    /**
     * The starred chats the lock lets through: what the Favourites tab lists, so its badge, its
     * counts and Clear all say the same number. A chat starred before the lock went on stays
     * starred, unseen, for when it comes off.
     */
    fun favourites(favourites: Set<Long>, allowed: Set<Long>?): Set<Long> =
        if (allowed == null) favourites else favourites.filterTo(LinkedHashSet()) { it in allowed }

    /** Continue watching, downloads and the other records of a video. */
    fun records(records: List<ResumeRecord>, allowed: Set<Long>?): List<ResumeRecord> = filter(records, allowed) { it.chatId }

    /**
     * Whether the lock is in force, from what is stored: switched on, and with a PIN to guard it.
     * A lock without a PIN could be switched off by anyone, so it is not honoured; Settings never
     * lets the two come apart, and this is the second line behind that.
     */
    fun inForce(on: Boolean, pinSet: Boolean): Boolean = on && pinSet
}

/**
 * The kids lock as the running process sees it, for the code that lists chats without a settings
 * store to hand: the repository's search and recent videos, and the shared chat list.
 *
 * Filled from the store by [follow], once per process (the Android `App`, the desktop `main`).
 * Until the first value arrives [awaitReady] holds a caller for a moment, so a cold start does
 * not flash every chat before the lock is read; in a process that never follows a store (the
 * tests, the promo fixture) it does not wait at all.
 */
object ChatGate {
    private val state = MutableStateFlow<Set<Long>?>(null)
    private val ready = MutableStateFlow(false)
    @Volatile
    private var following = false

    /**
     * The chats the kids lock allows, or null while it is off. This is the lock ALONE: a list that
     * is the viewer's own record (Continue watching, Watched, Watch later, History, Downloads) and
     * one chat they asked for by name reads this, so removing a chat from TMPlayer never loses the
     * progress or the files they already have. Everything that browses reads [browsing] instead.
     */
    val allowed: StateFlow<Set<Long>?> = state.asStateFlow()

    /**
     * What every list that browses reads (HX27): the kids lock and the chats the viewer added,
     * met in the middle, or null when neither narrows anything. Chats, Home and its rows, the
     * carousel, Recently added, search, a video's copies, the TV launcher's rows and the
     * background checks all ask this one question.
     */
    val browsing: StateFlow<Set<Long>?> by lazy {
        combine(state, AddedChats.ids) { kids, added -> AddedChats.gate(kids, added) }
            .stateIn(Background.scope, SharingStarted.Eagerly, AddedChats.gate(state.value, AddedChats.ids.value))
    }

    /** [browsing], after waiting briefly for both the lock and the choice to be read. */
    suspend fun awaitBrowsing(): Set<Long>? = AddedChats.gate(awaitReady(), AddedChats.awaitReady())

    /** Keeps [allowed] in step with [store] for the life of the process. */
    fun follow(store: AllowedChatsSource) {
        if (following) return
        following = true
        Background.scope.launch {
            store.kidsAllowedChats.collect { chats ->
                state.value = chats
                ready.value = true
            }
        }
    }

    /** Sets the gate directly. For tests, and for a process with no store to follow. */
    fun set(chats: Set<Long>?) {
        state.value = chats
        ready.value = true
    }

    /** Waits, briefly, for the stored lock to be read, then answers with it. */
    suspend fun awaitReady(): Set<Long>? {
        if (following && !ready.value) withTimeoutOrNull(READY_WAIT_MS) { ready.first { it } }
        return state.value
    }

    private const val READY_WAIT_MS = 2_000L
}

/** Where the kids lock's allowed chats are stored: [SettingsStore] in the apps. */
interface AllowedChatsSource {
    /** The chats the lock lets through, or null while it is off. */
    val kidsAllowedChats: kotlinx.coroutines.flow.Flow<Set<Long>?>
}

/** The app lock as Settings shows it. [kids] is only true with a PIN set ([KidsLock.inForce]). */
data class AppLockSettings(
    val pinSet: Boolean = false,
    val onOpen: Boolean = false,
    val kids: Boolean = false,
    val kidsChats: Set<Long> = emptySet(),
)
