package com.tmplayer.data

import com.tmplayer.platform.Logger
import com.tmplayer.platform.Paths
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val TAG = "AccountSwitcher"

/**
 * Who a profile signed in as, read once it is Ready: [profileId] is the profile that was open and
 * [session] the client generation it came from. Either no longer matching means a switch came in
 * between, and the answer is dropped ([AccountSwitcher.noteSignedIn]).
 */
data class SignedInAs(
    val profileId: String,
    val session: Long?,
    val telegramId: Long,
    val name: String,
    val photo: ByteArray?,
) {
    override fun equals(other: Any?) = other is SignedInAs && other.profileId == profileId && other.session == session &&
        other.telegramId == telegramId && other.name == name

    override fun hashCode() = (profileId.hashCode() * 31 + telegramId.hashCode()) * 31 + name.hashCode()
}

/** The one live TDLib client, as [AccountSwitcher] drives it. [TdClientHost] in the app, a fake in tests. */
interface ClientHost {
    /**
     * Closes the live client and opens the next one over [paths]. [onClosed] runs once the old one
     * has let go and before the next one exists. True once the new one is open; false when the old
     * one took longer than [timeoutMs] to close, in which case nothing else is opened yet and
     * [awaitOpen] waits for the close to finish.
     */
    suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean

    /** Waits for the client the last [reopen] asked for. */
    suspend fun awaitOpen()

    /** Logs the live client out of Telegram. True once TDLib has let go and waits for a sign in again. */
    suspend fun endSession(timeoutMs: Long): Boolean

    /** The live client's generation, or null between two clients. */
    fun sessionNow(): Long?

    /** Each time the live client is signed in. */
    val signedIn: Flow<SignedInAs>
}

/** Where a switch is, for the picker's spinner. */
sealed interface SwitchState {
    data object Idle : SwitchState
    data class Switching(val toId: String) : SwitchState

    /** The old client is taking long to close (a weak TV): still waiting, nothing else opened. */
    data class ClosingSlowly(val toId: String) : SwitchState
}

sealed interface SwitchResult {
    data object Switched : SwitchResult
    data object Already : SwitchResult

    /** A newer switch was asked for while this one waited: it never ran. */
    data object Superseded : SwitchResult
    data object Unknown : SwitchResult

    /** `Td.start` has not run, so there are no folders to open. */
    data object NotStarted : SwitchResult

    /** The profile's folders are missing or broken: offer Remove or Sign in again (`signInAgain = true`). */
    data class Recoverable(val state: ProfileState.Recoverable) : SwitchResult

    /** The folders could not be made. */
    data object Failed : SwitchResult

    /**
     * Closing the live client or pointing TDLib at the profile failed part way: the profile is
     * kept as it is and the one open before stays open. Never a reason to remove anything.
     */
    data object ReopenFailed : SwitchResult
}

sealed interface AddResult {
    data class Added(val profile: Profile) : AddResult

    /** Already [AccountRules.MAX] profiles. */
    data object Full : AddResult

    /** Not enough room for a new profile's folders: [neededBytes] asked, [freeBytes] free. */
    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : AddResult
    data object NotStarted : AddResult
    data object Failed : AddResult
}

sealed interface SignOutResult {
    /**
     * Signed out; [next] is the profile now open, or null when the sign in shows. [partialWipe]:
     * the platform's own clearing failed or was cut short (its backup, its downloads); the keys and
     * folders went anyway.
     */
    data class SignedOut(val next: Profile?, val partialWipe: Boolean = false) : SignOutResult
    data object Unknown : SignOutResult
    data object NotStarted : SignOutResult

    /** The profile could not be opened to sign it out (TDLib failed part way): it is kept, nothing was wiped. */
    data object Failed : SignOutResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data object Unknown : RemoveResult

    /** The open profile is signed out ([AccountSwitcher.signOutProfile]), not removed. */
    data object Active : RemoveResult

    /** The last profile cannot be removed; Remove all accounts signs it out. */
    data object Last : RemoveResult
}

/** What the picker is told without asking. */
sealed interface AccountEvent {
    /** A new profile signed in as the account [existing] already holds: refused, [existing] is open again. */
    data class SameAccount(val existing: Profile) : AccountEvent
}

/** Marks the platform's wipe, which runs under the switcher's lock and so must never call the switcher. */
private class InsideSwitcher : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InsideSwitcher>
}

/**
 * Switching, adding, signing out and removing profiles (HX88, docs/agents/multi-account.md).
 *
 * Every change runs under one [Mutex] and outside its caller's scope (a screen that rotates or
 * leaves does not stop a switch half way). Switches that queue up behind one another keep only the
 * newest: the last switch asked for wins, and those in between never open a client.
 *
 * The open profile ([AccountList.activeId], and with it every store's view and [Accounts.stamp])
 * changes only between the old client's close and the next client, never while either runs.
 *
 * [wipe] in [signOutProfile] and [removeAll] is the platform's sign out (its backup, its stores,
 * its downloads choice), run while the profile is the open one, so every store it touches is that
 * profile's. It runs under the lock: it must never call this switcher, and a call from it is refused.
 */
class AccountSwitcher(
    private val book: AccountBook,
    private val host: ClientHost,
    private val base: () -> Paths?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    private val onSleepingBytes: (Long) -> Unit = {},
) {
    private val mutex = Mutex()
    private val asked = AtomicLong(0)
    private val _state = MutableStateFlow<SwitchState>(SwitchState.Idle)
    val state: StateFlow<SwitchState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AccountEvent> = _events.asSharedFlow()

    val profiles: StateFlow<AccountList> get() = book.list

    /** Whether the picker shows at launch: two profiles or more (one never sees it). */
    val pickerAtLaunch: Boolean get() = book.list.value.profiles.size >= 2

    /** Each profile's folders as they are now, for the picker. */
    fun stateOf(profile: Profile): ProfileState {
        val base = base() ?: return ProfileState.Ready
        return AccountDirs.stateOf(AccountDirs.pathsFor(base, profile), profile)
    }

    /** True, and logged, when called from inside a wipe: the lock is held and waiting for it would never end. */
    private suspend fun reentered(): Boolean {
        if (currentCoroutineContext()[InsideSwitcher] == null) return false
        Logger.w(TAG, "the switcher was called from inside a sign out's wipe; refused")
        return true
    }

    /** Opens [id]'s client. With [signInAgain] a broken profile's folders are made afresh and the sign in opens. */
    suspend fun switchTo(id: String, signInAgain: Boolean = false): SwitchResult {
        if (reentered()) return SwitchResult.Failed
        val ticket = asked.incrementAndGet()
        return withContext(NonCancellable) {
            mutex.withLock {
                if (ticket != asked.get()) SwitchResult.Superseded else switchLocked(id, signInAgain)
            }
        }
    }

    /**
     * [signInAgain] wipes the folders of a [ProfileState.Recoverable] profile; only ever true for
     * the viewer's own Sign in again and for a profile being made, never for one picked by the code.
     */
    private suspend fun switchLocked(id: String, signInAgain: Boolean): SwitchResult {
        val list = book.list.value
        val target = list.byId(id) ?: return SwitchResult.Unknown
        if (list.activeId == id && list.switchingTo == null) return SwitchResult.Already
        val base = base() ?: return SwitchResult.NotStarted
        val paths = AccountDirs.pathsFor(base, target)
        val state = AccountDirs.stateOf(paths, target)
        if (state is ProfileState.Recoverable) {
            if (!signInAgain) return SwitchResult.Recoverable(state)
            AccountDirs.wipe(paths)
        }
        if (!withContext(Dispatchers.IO) { AccountDirs.make(paths) }) return SwitchResult.Failed
        // Only where it is going: a process killed before the old client closes reopens activeId.
        book.update { it.copy(switchingTo = id) }
        _state.value = SwitchState.Switching(id)
        val flip = { book.update { l -> if (l.byId(id) == null) l.copy(switchingTo = null) else l.copy(activeId = id, switchingTo = null) } }
        try {
            if (!host.reopen(paths, closeTimeoutMs, onClosed = { flip() })) {
                Logger.i(TAG, "the last client is slow to close; waiting for it before the next one opens")
                _state.value = SwitchState.ClosingSlowly(id)
                host.awaitOpen()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "the client could not be reopened for another profile", e)
            if (book.list.value.activeId != id) return SwitchResult.ReopenFailed
        } finally {
            // A host that never ran the hook (an error part way) moved nothing: only where it was
            // going is forgotten, and the stores stay with the profile still open.
            if (book.list.value.switchingTo == id) book.update { it.copy(switchingTo = null) }
            _state.value = SwitchState.Idle
        }
        measureSleeping(base)
        return SwitchResult.Switched
    }

    /** A new profile, opened at once on the sign in. Refused at [AccountRules.MAX] and without room. */
    suspend fun add(): AddResult {
        if (reentered()) return AddResult.Failed
        asked.incrementAndGet()
        return withContext(NonCancellable) {
            mutex.withLock {
                val list = book.list.value
                if (!AccountRules.canAdd(list)) return@withLock AddResult.Full
                val base = base() ?: return@withLock AddResult.NotStarted
                val disk = withContext(Dispatchers.IO) { runCatching { base.disk() }.getOrDefault(DiskInfo.EMPTY) }
                // An unreadable disk (0 total) is not a full one: it is let through, and TDLib says so if it is.
                if (disk.totalBytes > 0 && disk.freeBytes < minFreeBytes) return@withLock AddResult.NoSpace(minFreeBytes, disk.freeBytes)
                val folder = AccountRules.newFolder(list)
                val profile = Profile(id = folder, folder = folder, createdAt = clock(), adding = true)
                book.update { it.copy(profiles = it.profiles + profile) }
                // A new folder: wiping a half made one of the same name is what Sign in again is for.
                when (switchLocked(profile.id, signInAgain = true)) {
                    SwitchResult.Switched -> AddResult.Added(profile)
                    else -> {
                        book.update { AccountRules.removed(it, profile) }
                        book.sweep(base)
                        AddResult.Failed
                    }
                }
            }
        }
    }

    /**
     * Records who the open profile signed in as, and its small photo. An answer for another profile
     * or another client than the live one is dropped. The same Telegram account on a second profile
     * is refused: this new session is logged out, its folders go, and the profile that already
     * holds the account opens again ([AccountEvent.SameAccount]).
     */
    suspend fun noteSignedIn(who: SignedInAs) {
        if (reentered()) return
        withContext(NonCancellable) {
            mutex.withLock {
                val list = book.list.value
                val active = list.active ?: return@withLock
                if (who.profileId != active.id || list.switchingTo != null || who.session != host.sessionNow()) {
                    Logger.i(TAG, "who signed in arrived after a switch; dropped")
                    return@withLock
                }
                // Signed in, who as yet unknown (the read failed or is still on its way): a real
                // profile now, kept at the next start; the identity follows when it is read.
                if (who.telegramId == 0L) {
                    if (active.adding) book.update { l -> l.byId(active.id)?.let { l.replace(it.copy(adding = false)) } ?: l }
                    return@withLock
                }
                val existing = AccountRules.sameTelegram(list, who.telegramId, active.id)
                if (existing != null) {
                    refuseSameAccount(active, existing)
                    return@withLock
                }
                val photo = who.photo?.let { bytes -> withContext(Dispatchers.IO) { keepPhoto(active, bytes) } } ?: active.photo
                book.update { l ->
                    val now = l.byId(active.id) ?: return@update l
                    l.replace(now.copy(telegramId = who.telegramId, name = who.name.take(128), photo = photo, adding = false))
                }
            }
        }
    }

    private suspend fun refuseSameAccount(active: Profile, existing: Profile) {
        Logger.i(TAG, "a new profile signed in as an account another profile holds; refused")
        host.endSession(LOG_OUT_TIMEOUT_MS)
        val base = base()
        // Never wiped: the existing profile is opened as it is, or not at all.
        if (switchLocked(existing.id, signInAgain = false) == SwitchResult.Switched) {
            book.update { AccountRules.removed(it, active, existing.id) }
            base?.let { book.sweep(it) }
        } else {
            // The existing profile cannot be opened: this one stays open on its sign in, with nobody in it.
            book.update { l -> l.byId(active.id)?.let { l.replace(it.copy(telegramId = 0L, name = "", photo = null)) } ?: l }
        }
        _events.tryEmit(AccountEvent.SameAccount(existing))
    }

    /** Listens for each sign in of the live client and records it ([noteSignedIn]). Call once per process. */
    fun attach(scope: CoroutineScope) {
        scope.launch {
            host.signedIn.collect { who ->
                try {
                    noteSignedIn(who)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "recording who signed in failed", e)
                }
            }
        }
    }

    /**
     * Signs [id] out of Telegram and off this device. [wipe] runs with [id] open and is told whether
     * it is the last profile ([SettingsStore.clearEverything] then, [SettingsStore.clearAccountData]
     * otherwise); it runs under this switcher's lock and must not call it. Another profile opens
     * after, or the sign in when it was the last or no other one can be opened.
     */
    suspend fun signOutProfile(id: String, wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        if (reentered()) return SignOutResult.Unknown
        asked.incrementAndGet()
        return withContext(NonCancellable) { mutex.withLock { signOutLocked(id, wipe) } }
    }

    private suspend fun signOutLocked(id: String, wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        val base = base() ?: return SignOutResult.NotStarted
        val before = book.list.value
        val target = before.byId(id) ?: return SignOutResult.Unknown
        val previous = before.activeId
        if (before.activeId != id) {
            when (switchLocked(id, signInAgain = false)) {
                SwitchResult.Switched, SwitchResult.Already -> Unit
                // TDLib failed part way, or the folders could not be made (a full disk): the
                // profile's session may well be alive, so it stays, with nothing wiped, and says so.
                SwitchResult.ReopenFailed, SwitchResult.Failed -> return SignOutResult.Failed
                // Folders that cannot be opened have no session to end: the profile just goes, and
                // the live client, another profile's, never held them.
                else -> {
                    book.update { AccountRules.removed(it, target, previous) }
                    book.sweep(base)
                    return SignOutResult.SignedOut(book.list.value.active)
                }
            }
        }
        val others = book.list.value.profiles.filter { it.id != id }
        // Under NonCancellable, so a cancellation here is the wipe's own (a timeout inside it), never
        // the caller's: the sign out goes on, and says the wipe was cut short.
        var partial = false
        try {
            withContext(InsideSwitcher()) { wipe(target, others.isEmpty()) }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            partial = true
            Logger.w(TAG, "the sign out's own clearing was cut short; the rest of the sign out goes on", e)
        } catch (e: Exception) {
            partial = true
            Logger.w(TAG, "the sign out's own clearing failed; the sweep clears the profile's keys", e)
        }
        if (!host.endSession(LOG_OUT_TIMEOUT_MS)) Logger.w(TAG, "TDLib was slow to finish logging out")
        // The next profile, the one open before first; never wiped, since none of them asked to sign in again.
        val candidates = others.sortedBy { if (it.id == previous) 0 else 1 }
        val next = candidates.firstOrNull { switchLocked(it.id, signInAgain = false) == SwitchResult.Switched }
        if (next == null) {
            // The last profile, or none of the others can be opened: this one stays, empty, on the sign in.
            book.update { l ->
                l.byId(id)?.let {
                    l.replace(it.copy(telegramId = 0L, name = "", photo = null, adding = false, pin = null, pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L))
                } ?: l
            }
            target.photo?.let { runCatching { File(it).delete() } }
            return SignOutResult.SignedOut(null, partial)
        }
        // Only now that another client holds the live folders does this profile's go.
        book.update { AccountRules.removed(it, target, next.id) }
        book.sweep(base)
        measureSleeping(base)
        return SignOutResult.SignedOut(book.list.value.byId(next.id), partial)
    }

    /** Forgets a sleeping profile without reaching Telegram: one whose folders are broken, or one never signed in. */
    suspend fun remove(id: String): RemoveResult {
        if (reentered()) return RemoveResult.Unknown
        return withContext(NonCancellable) {
            mutex.withLock {
                val list = book.list.value
                val target = list.byId(id) ?: return@withLock RemoveResult.Unknown
                if (list.profiles.size <= 1) return@withLock RemoveResult.Last
                if (list.activeId == id || list.switchingTo == id) return@withLock RemoveResult.Active
                book.update { AccountRules.removed(it, target) }
                base()?.let { book.sweep(it) }
                RemoveResult.Removed
            }
        }
    }

    /**
     * Remove all accounts: every profile signed out in turn (the open one last), then one empty
     * profile on the platform's own folders, on the normal sign in.
     */
    suspend fun removeAll(wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        if (reentered()) return SignOutResult.Unknown
        asked.incrementAndGet()
        return withContext(NonCancellable) {
            mutex.withLock {
                val base = base() ?: return@withLock SignOutResult.NotStarted
                val start = book.list.value
                val order = start.profiles.filter { it.id != start.activeId } + listOfNotNull(start.active)
                // Whether any profile's own clearing was cut short, said once for the lot.
                var partial = false
                for (profile in order) {
                    if (book.list.value.byId(profile.id) == null) continue
                    val result = signOutLocked(profile.id, wipe)
                    // One that could not be opened is kept as it is: its session may be alive, and
                    // the fresh start below would forget it. What was signed out stays signed out.
                    if (result is SignOutResult.Failed) return@withLock SignOutResult.Failed
                    if (result is SignOutResult.SignedOut && result.partialWipe) partial = true
                }
                val left = book.list.value
                val adoptedLeft = left.profiles.singleOrNull()?.adopted == true
                if (!adoptedLeft) {
                    // Back on the platform's own folders as a fresh install would be: whatever an
                    // earlier adopted account left in them, and in the stores' plain keys, goes first.
                    val fresh = Profile(AccountRules.ADOPTED_ID, folder = null, createdAt = clock())
                    withContext(Dispatchers.IO) { AccountDirs.wipe(base) }
                    book.purgeKeys("")
                    withContext(Dispatchers.IO) { AccountDirs.make(base) }
                    book.update {
                        it.copy(
                            profiles = it.profiles + fresh,
                            switchingTo = fresh.id,
                            trash = it.trash - "",
                        )
                    }
                    val flip = {
                        book.update { l -> AccountList(listOf(fresh), fresh.id, trash = (l.trash + l.profiles.filter { !it.adopted }.map { it.folder ?: "" }).distinct()) }
                    }
                    if (!host.reopen(base, closeTimeoutMs, onClosed = { flip() })) host.awaitOpen()
                    if (book.list.value.switchingTo != null) book.update { it.copy(switchingTo = null) }
                    book.sweep(base)
                }
                onSleepingBytes(0L)
                SignOutResult.SignedOut(null, partial)
            }
        }
    }

    /**
     * Gives a profile a name of its own on this device ([Profile.label]), or Telegram's again for
     * null or blank. False when the profile is gone. Who it signs in as is never changed by it.
     */
    suspend fun rename(id: String, label: String?): Boolean = withContext(Dispatchers.Default) {
        val clean = label?.let(AccountRules::cleanLabel)
        var done = false
        book.update { l ->
            val now = l.byId(id) ?: return@update l
            done = true
            l.replace(now.copy(label = clean))
        }
        done
    }

    /** Sets or clears ([pin] null) a profile's PIN. False when it is not 4 digits or the profile is gone. */
    suspend fun setPin(id: String, pin: String?): Boolean = withContext(Dispatchers.Default) {
        val profile = book.list.value.byId(id) ?: return@withContext false
        // Hashed outside the list's lock; only the PIN fields are written, onto the profile as it is then.
        val made = ProfilePin.set(profile, pin) ?: return@withContext false
        var done = false
        book.update { l ->
            val now = l.byId(id) ?: return@update l
            done = true
            l.replace(now.copy(pin = made.pin, pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L))
        }
        done
    }

    /**
     * Checks a profile's PIN at the pad: five wrong and the pad waits 30 seconds ([ProfilePin]).
     * Checked and counted inside one change of the list, so two tries at once both count.
     */
    suspend fun checkPin(id: String, pin: String, now: Long = PinClock.now()): PinCheck = withContext(Dispatchers.Default) {
        var check: PinCheck = PinCheck.NoPin
        book.update { l ->
            val profile = l.byId(id) ?: return@update l
            val (next, answer) = ProfilePin.check(profile, pin, now)
            check = answer
            if (next == profile) l else l.replace(next)
        }
        check
    }

    private fun keepPhoto(profile: Profile, bytes: ByteArray): String? = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) return@runCatching profile.photo
        val dir = book.photoDir.apply { mkdirs() }
        val file = File(dir, "${profile.folder ?: profile.id}.jpg")
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        file.path
    }.getOrNull()

    private suspend fun measureSleeping(base: Paths) {
        val list = book.list.value
        val bytes = withContext(Dispatchers.IO) {
            list.profiles.filter { it.id != list.activeId }
                .sumOf { AccountDirs.bytesUnder(AccountDirs.pathsFor(base, it).filesDir) }
        }
        onSleepingBytes(bytes)
    }

    companion object {
        /** How long a close may take before the picker says it is slow. It is waited out either way. */
        const val CLOSE_TIMEOUT_MS = 20_000L
        const val LOG_OUT_TIMEOUT_MS = 30_000L

        /** Room for a new database and the first of a cache. */
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val MAX_PHOTO_BYTES = 512 * 1024

        @Volatile
        private var process: AccountSwitcher? = null

        /**
         * The process's switcher over [Td], once [Accounts.open] has run; null before. Phase B's
         * screens call this.
         */
        fun current(): AccountSwitcher? = process ?: synchronized(this) {
            process ?: Accounts.book?.let { book ->
                AccountSwitcher(book, TdClientHost, base = { Td.platformPaths }, onSleepingBytes = { Td.sleepingCacheBytes = it })
                    .also { process = it }
            }
        }
    }
}

/** A client that reached Ready: the profile open then and the client's generation. */
data class ReadySession(val profileId: String, val generation: Long)

/** How the live client's sign ins become [SignedInAs]: apart from Td, so a test can drive it. */
internal object SignedInFlow {
    /**
     * For each Ready client, once: first a marker with no identity (the profile is a signed in one
     * now, whatever the read does), then who it is, read again after [gapMs] up to [tries] times
     * when the read fails. Keyed on the client's generation, and a newer one cancels the read of an
     * older one, so no Ready is swallowed by a slow read and no answer outlives its client.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun of(
        ready: Flow<ReadySession>,
        read: suspend (ReadySession) -> SignedInAs?,
        tries: Int = 4,
        gapMs: Long = 20_000L,
    ): Flow<SignedInAs> = ready.distinctUntilChanged().transformLatest { r ->
        emit(SignedInAs(r.profileId, r.generation, 0L, "", null))
        repeat(tries) {
            val who = try {
                read(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (who != null && who.telegramId != 0L) {
                emit(who)
                return@transformLatest
            }
            delay(gapMs)
        }
    }
}

/** [ClientHost] over the process's [Td]. */
object TdClientHost : ClientHost {
    @Volatile
    private var opened: CompletableDeferred<Long>? = null

    /**
     * Waits for the client made after the switch's hook ran. The hook records the generation count
     * as it runs, under Td's client lock, so a client made before it (one about to close) never
     * counts, whether or not a client was live when the switch began.
     */
    override suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean {
        val carried = runCatching { Td.proxies() }.getOrDefault(emptyList())
        val ran = CompletableDeferred<Long>()
        opened = ran
        Td.reopenFor(paths) {
            try {
                onClosed()
            } finally {
                ran.complete(Td.generationsMade)
            }
        }
        val done = withTimeoutOrNull(timeoutMs) { awaitAfter(ran) } != null
        if (done) carryProxies(carried)
        return done
    }

    private suspend fun awaitAfter(ran: CompletableDeferred<Long>) {
        val made = ran.await()
        Td.sessions.first { it != null && it.generation > made }
    }

    override suspend fun awaitOpen() {
        opened?.let { awaitAfter(it) }
    }

    override suspend fun endSession(timeoutMs: Long): Boolean {
        val was = Td.generationsMade
        Td.logOut()
        return withTimeoutOrNull(timeoutMs) { Td.sessions.first { it != null && it.generation > was } } != null
    }

    override fun sessionNow(): Long? = Td.sessionGeneration

    override val signedIn: Flow<SignedInAs> = SignedInFlow.of(
        ready = combine(Td.auth, Td.sessions) { auth, session ->
            session?.takeIf { auth is AuthState.Ready }?.let { ReadySession(Accounts.active.id, it.generation) }
        }.filterNotNull(),
        read = ::readSignedIn,
    )

    /** The proxies are the device's (HX88): ones the next profile's database lacks are added to it. */
    private suspend fun carryProxies(from: List<ProxyEntry>) {
        if (from.isEmpty()) return
        val have = runCatching { Td.proxies() }.getOrDefault(emptyList())
        val anyOn = have.any { it.enabled }
        for (entry in from) {
            if (have.any { it.spec == entry.spec }) continue
            runCatching { Td.addProxy(entry.spec, enable = entry.enabled && !anyOn) }
        }
    }

    /** Who [ready]'s client is signed in as; null when that client is gone or Telegram does not say. */
    private suspend fun readSignedIn(ready: ReadySession): SignedInAs? {
        val session = withTimeoutOrNull(READ_TIMEOUT_MS) { Td.awaitAuthorizedSession() } ?: return null
        if (session.generation != ready.generation) return null
        val me = Td.me(session) ?: return null
        val id = Td.myId().takeIf { it != 0L } ?: return null
        val photo = me.photoFileId.takeIf { it > 0 }?.let { fileId ->
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                session.client.downloadFile(fileId, PHOTO_PRIORITY, 0, 0, true)
                (session.client.readFilePart(fileId, 0, 0) as? TdlResult.Success)?.result?.data
            }
        } ?: me.miniThumbnail
        return SignedInAs(ready.profileId, ready.generation, id, me.name, photo)
    }

    private const val READ_TIMEOUT_MS = 15_000L
    private const val PHOTO_PRIORITY = 1
}
