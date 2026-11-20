package com.tmplayer.data

import com.tmplayer.platform.Logger
import com.tmplayer.platform.Paths
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AccountSwitcher"

/** Who a profile signed in as, read once it is Ready. */
data class SignedInAs(val telegramId: Long, val name: String, val photo: ByteArray?) {
    override fun equals(other: Any?) = other is SignedInAs && other.telegramId == telegramId && other.name == name
    override fun hashCode() = telegramId.hashCode() * 31 + name.hashCode()
}

/** The one live TDLib client, as [AccountSwitcher] drives it. [TdClientHost] in the app, a fake in tests. */
interface ClientHost {
    /**
     * Closes the live client and opens the next one over [paths]. True once the new one is open;
     * false when the old one took longer than [timeoutMs] to close, in which case nothing else is
     * opened yet and [awaitOpen] waits for the close to finish.
     */
    suspend fun reopen(paths: Paths, timeoutMs: Long): Boolean

    /** Waits for the client the last [reopen] asked for. */
    suspend fun awaitOpen()

    /** Logs the live client out of Telegram. True once TDLib has let go and waits for a sign in again. */
    suspend fun endSession(timeoutMs: Long): Boolean

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
    /** Signed out; [next] is the profile now open, or null when it was the last and the sign in shows. */
    data class SignedOut(val next: Profile?) : SignOutResult
    data object Unknown : SignOutResult
    data object NotStarted : SignOutResult
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

/**
 * Switching, adding, signing out and removing profiles (HX88, docs/agents/multi-account.md).
 *
 * Every change runs under one [Mutex] and outside its caller's scope (a screen that rotates or
 * leaves does not stop a switch half way). Switches that queue up behind one another keep only the
 * newest: the last switch asked for wins, and those in between never open a client.
 *
 * [wipe] in [signOutProfile] and [removeAll] is the platform's sign out (its backup, its stores,
 * its downloads choice), run while the profile is the open one, so every store it touches is that
 * profile's.
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

    /** Opens [id]'s client. With [signInAgain] a broken profile's folders are made afresh and the sign in opens. */
    suspend fun switchTo(id: String, signInAgain: Boolean = false): SwitchResult {
        val ticket = asked.incrementAndGet()
        return withContext(NonCancellable) {
            mutex.withLock {
                if (ticket != asked.get()) SwitchResult.Superseded else switchLocked(id, signInAgain)
            }
        }
    }

    private suspend fun switchLocked(id: String, signInAgain: Boolean): SwitchResult {
        val list = book.list.value
        val target = list.byId(id) ?: return SwitchResult.Unknown
        if (list.activeId == id && list.switchingFrom == null) return SwitchResult.Already
        val base = base() ?: return SwitchResult.NotStarted
        val paths = AccountDirs.pathsFor(base, target)
        val state = AccountDirs.stateOf(paths, target)
        if (state is ProfileState.Recoverable) {
            if (!signInAgain) return SwitchResult.Recoverable(state)
            AccountDirs.wipe(paths)
        }
        if (!withContext(Dispatchers.IO) { AccountDirs.make(paths) }) return SwitchResult.Failed
        // Written before the client is touched: a process killed from here on reopens the profile left.
        book.update { it.copy(activeId = id, switchingFrom = it.switchingFrom ?: it.activeId) }
        _state.value = SwitchState.Switching(id)
        try {
            if (!host.reopen(paths, closeTimeoutMs)) {
                Logger.i(TAG, "the last client is slow to close; waiting for it before the next one opens")
                _state.value = SwitchState.ClosingSlowly(id)
                host.awaitOpen()
            }
            book.update { it.copy(switchingFrom = null) }
        } finally {
            _state.value = SwitchState.Idle
        }
        measureSleeping(base)
        return SwitchResult.Switched
    }

    /** A new profile, opened at once on the sign in. Refused at [AccountRules.MAX] and without room. */
    suspend fun add(): AddResult {
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
     * Records who the open profile signed in as, and its small photo. The same Telegram account on
     * a second profile is refused: this new session is logged out, its folders go, and the profile
     * that already holds the account opens again ([AccountEvent.SameAccount]).
     */
    suspend fun noteSignedIn(who: SignedInAs) {
        if (who.telegramId == 0L) return
        withContext(NonCancellable) {
            mutex.withLock {
                val list = book.list.value
                val active = list.active ?: return@withLock
                val existing = AccountRules.sameTelegram(list, who.telegramId, active.id)
                if (existing != null) {
                    Logger.i(TAG, "a new profile signed in as an account another profile holds; refused")
                    host.endSession(LOG_OUT_TIMEOUT_MS)
                    switchLocked(existing.id, signInAgain = true)
                    book.update { AccountRules.removed(it, active, existing.id) }
                    base()?.let(book::sweep)
                    _events.tryEmit(AccountEvent.SameAccount(existing))
                    return@withLock
                }
                val photo = who.photo?.let { bytes -> withContext(Dispatchers.IO) { keepPhoto(active, bytes) } } ?: active.photo
                book.update {
                    it.replace(active.copy(telegramId = who.telegramId, name = who.name.take(128), photo = photo, adding = false))
                }
            }
        }
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
     * otherwise). Another profile opens after, or the sign in when it was the last.
     */
    suspend fun signOutProfile(id: String, wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        asked.incrementAndGet()
        return withContext(NonCancellable) { mutex.withLock { signOutLocked(id, wipe) } }
    }

    private suspend fun signOutLocked(id: String, wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        val base = base() ?: return SignOutResult.NotStarted
        val before = book.list.value
        val target = before.byId(id) ?: return SignOutResult.Unknown
        val previous = before.activeId
        val others = before.profiles.filter { it.id != id }
        if (before.activeId != id) {
            when (switchLocked(id, signInAgain = false)) {
                SwitchResult.Switched, SwitchResult.Already -> Unit
                // Folders that cannot be opened have no session to end: the profile just goes.
                else -> {
                    book.update { AccountRules.removed(it, target, previous) }
                    book.sweep(base)
                    return SignOutResult.SignedOut(book.list.value.active)
                }
            }
        }
        runCatching { wipe(target, others.isEmpty()) }.onFailure { Logger.w(TAG, "the sign out's own clearing failed", it) }
        if (!host.endSession(LOG_OUT_TIMEOUT_MS)) Logger.w(TAG, "TDLib was slow to finish logging out")
        if (others.isEmpty()) {
            book.update { it.replace(target.copy(telegramId = 0L, name = "", photo = null, adding = false, pin = null, pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L)) }
            target.photo?.let { runCatching { File(it).delete() } }
            return SignOutResult.SignedOut(null)
        }
        val next = others.firstOrNull { it.id == previous } ?: others.first()
        switchLocked(next.id, signInAgain = true)
        book.update { AccountRules.removed(it, target, next.id) }
        book.sweep(base)
        measureSleeping(base)
        return SignOutResult.SignedOut(book.list.value.byId(next.id))
    }

    /** Forgets a sleeping profile without reaching Telegram: one whose folders are broken, or one never signed in. */
    suspend fun remove(id: String): RemoveResult = withContext(NonCancellable) {
        mutex.withLock {
            val list = book.list.value
            val target = list.byId(id) ?: return@withLock RemoveResult.Unknown
            if (list.profiles.size <= 1) return@withLock RemoveResult.Last
            if (list.activeId == id) return@withLock RemoveResult.Active
            book.update { AccountRules.removed(it, target) }
            base()?.let(book::sweep)
            RemoveResult.Removed
        }
    }

    /**
     * Remove all accounts: every profile signed out in turn (the open one last), then one empty
     * profile on the platform's own folders, on the normal sign in.
     */
    suspend fun removeAll(wipe: suspend (Profile, last: Boolean) -> Unit): SignOutResult {
        asked.incrementAndGet()
        return withContext(NonCancellable) {
            mutex.withLock {
                val base = base() ?: return@withLock SignOutResult.NotStarted
                val start = book.list.value
                val order = start.profiles.filter { it.id != start.activeId } + listOfNotNull(start.active)
                for (profile in order) signOutLocked(profile.id, wipe)
                val left = book.list.value.active
                if (left != null && !left.adopted) {
                    // Back on the platform's own folders, as a fresh install would be.
                    val fresh = Profile(AccountRules.ADOPTED_ID, folder = null, createdAt = clock())
                    book.update { AccountList(listOf(fresh), fresh.id, switchingFrom = left.id, trash = it.trash + (left.folder ?: "")) }
                    withContext(Dispatchers.IO) { AccountDirs.make(base) }
                    if (!host.reopen(base, closeTimeoutMs)) host.awaitOpen()
                    book.update { it.copy(switchingFrom = null) }
                    book.sweep(base)
                }
                onSleepingBytes(0L)
                SignOutResult.SignedOut(null)
            }
        }
    }

    /** Sets or clears ([pin] null) a profile's PIN. False when it is not 4 digits or the profile is gone. */
    suspend fun setPin(id: String, pin: String?): Boolean = withContext(Dispatchers.Default) {
        val profile = book.list.value.byId(id) ?: return@withContext false
        val next = ProfilePin.set(profile, pin) ?: return@withContext false
        book.update { it.replace(next) }
        true
    }

    /** Checks a profile's PIN at the pad: five wrong and the pad waits 30 seconds ([ProfilePin]). */
    suspend fun checkPin(id: String, pin: String, now: Long = PinClock.now()): PinCheck = withContext(Dispatchers.Default) {
        val profile = book.list.value.byId(id) ?: return@withContext PinCheck.NoPin
        val (next, check) = ProfilePin.check(profile, pin, now)
        if (next != profile) book.update { list -> list.byId(id)?.let { list.replace(next) } ?: list }
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

/** [ClientHost] over the process's [Td]. */
object TdClientHost : ClientHost {
    @Volatile
    private var before: Long? = null

    override suspend fun reopen(paths: Paths, timeoutMs: Long): Boolean {
        val carried = runCatching { Td.proxies() }.getOrDefault(emptyList())
        before = Td.sessionGeneration
        Td.reopenFor(paths)
        val opened = withTimeoutOrNull(timeoutMs) { Td.awaitSessionAfter(before) } != null
        if (opened) carryProxies(carried)
        return opened
    }

    override suspend fun awaitOpen() = Td.awaitSessionAfter(before)

    override suspend fun endSession(timeoutMs: Long): Boolean {
        val was = Td.sessionGeneration
        Td.logOut()
        return withTimeoutOrNull(timeoutMs) { Td.awaitSessionAfter(was) } != null
    }

    override val signedIn: Flow<SignedInAs> = Td.auth
        .map { it is AuthState.Ready }
        .distinctUntilChanged()
        .filter { it }
        .mapNotNull { runCatching { readSignedIn() }.getOrNull() }

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

    private suspend fun readSignedIn(): SignedInAs? {
        val session = withTimeoutOrNull(READ_TIMEOUT_MS) { Td.awaitAuthorizedSession() } ?: return null
        val me = Td.me(session) ?: return null
        val id = Td.myId().takeIf { it != 0L } ?: return null
        val photo = me.photoFileId.takeIf { it > 0 }?.let { fileId ->
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                session.client.downloadFile(fileId, PHOTO_PRIORITY, 0, 0, true)
                (session.client.readFilePart(fileId, 0, 0) as? TdlResult.Success)?.result?.data
            }
        } ?: me.miniThumbnail
        return SignedInAs(id, me.name, photo)
    }

    private const val READ_TIMEOUT_MS = 15_000L
    private const val PHOTO_PRIORITY = 1
}
