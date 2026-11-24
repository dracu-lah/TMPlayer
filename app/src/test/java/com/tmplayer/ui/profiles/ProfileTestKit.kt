package com.tmplayer.ui.profiles

import com.tmplayer.data.AccountBook
import com.tmplayer.data.AccountStore
import com.tmplayer.data.AccountSwitcher
import com.tmplayer.data.Accounts
import com.tmplayer.data.AddResult
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.ClientHost
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.Profile
import com.tmplayer.data.SignedInAs
import com.tmplayer.platform.Paths
import com.tmplayer.ui.lock.PinChecker
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** A platform's folders under one temporary directory. */
internal class KitPaths(val root: File = Files.createTempDirectory("profiles").toFile()) : Paths {
    override val databaseDir: File get() = File(root, "tdlib")
    override val filesDir: File get() = File(root, "cache")
    override val downloadsDir: File get() = File(root, "downloads")
    @Volatile var free: Long = 50L shl 30
    override fun disk(): DiskInfo = DiskInfo(free, 100L shl 30)

    init {
        databaseDir.mkdirs()
    }
}

/** The one TDLib client, at once: a close is over as soon as it is asked for. */
internal class KitHost : ClientHost {
    @Volatile
    var generation = 1L

    /** Set: closing the client fails part way, as a TDLib refusing would. */
    @Volatile var failReopen = false

    override suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean {
        if (failReopen) throw IllegalStateException("TDLib could not be closed")
        onClosed()
        paths.databaseDir.mkdirs()
        generation++
        return true
    }

    override suspend fun awaitOpen() = Unit

    /** Set: logging out throws, as a TDLib failing part way would. */
    @Volatile var failEndSession = false

    override suspend fun endSession(timeoutMs: Long): Boolean {
        if (failEndSession) throw IllegalStateException("TDLib could not log out")
        generation++
        return true
    }

    override fun sessionNow(): Long? = generation

    override val signedIn: Flow<SignedInAs> = emptyFlow()
}

/** What the platform does, recorded. */
internal class KitPlatform : ProfilePlatform {
    @Volatile var playing = false
    @Volatile var downloading = false
    @Volatile var stopped = 0
    val wiped = java.util.Collections.synchronizedList(mutableListOf<String>())
    val said = java.util.Collections.synchronizedList(mutableListOf<String>())
    val lock = MutableStateFlow<AppLockSettings?>(null)

    override fun playing() = playing
    override fun downloading() = downloading
    /** Set: the work before a switch does not stop until it completes (a sync that is slow to stop). */
    @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** What happened, in order: "wait" for each wait before a switch, "resume" for each resume. */
    val order = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun beforeSwitch(onWaiting: (List<String>) -> Unit) {
        order += "wait"
        hold?.let { waiting ->
            onWaiting(listOf("a sync"))
            waiting.await()
        }
        stopped++
        playing = false
        downloading = false
    }
    @Volatile var resumed = 0

    override suspend fun switchCancelled() {
        // Slow on purpose: a retry that did not wait for it would start its wait first.
        kotlinx.coroutines.delay(200)
        order += "resume"
        resumed++
    }

    override suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean) {
        wiped += profile.id
    }
    /** Set: saying something throws this, as a failing platform would. */
    @Volatile var sayFails: Exception? = null

    override fun say(text: String) {
        sayFails?.let { throw it }
        said += text
    }
    /** What the store says as it changes; a test may make it say nothing for a while. */
    @Volatile var lockFlow: Flow<AppLockSettings?>? = null

    /** False: the store cannot be read at once (a slow first read). */
    @Volatile var readable = true

    override val appLock: Flow<AppLockSettings?> get() = lockFlow ?: lock

    override fun appLockNow(): AppLockSettings? = if (readable) lock.value ?: AppLockSettings() else null

    /** The app lock's PIN for this test, or null for none. */
    @Volatile var appPin: String? = null
        set(value) {
            field = value
            lock.value = if (value == null) null else AppLockSettings(pinSet = true)
        }

    override fun appLockChecker(): PinChecker? = object : PinChecker {
        override suspend fun waitUntil(): Long = 0L
        override suspend fun check(pin: String): com.tmplayer.data.PinCheck =
            if (pin == appPin) com.tmplayer.data.PinCheck.Right else com.tmplayer.data.PinCheck.Wrong(com.tmplayer.data.PinAttempts(1))
        override fun freeLeft(attempts: com.tmplayer.data.PinAttempts): Int = 3
    }
}

/** Profiles over a fake client, signed in as [names] (the first is the profile adopted in place). */
internal class ProfileKit(vararg names: String) {
    val dir: File = Files.createTempDirectory("profile-kit").toFile()
    val base = KitPaths()
    val host = KitHost()
    val book = AccountBook(AccountStore(File(dir, Accounts.FILE_NAME)))
    val switcher = AccountSwitcher(book, host, base = { base }, closeTimeoutMs = 2_000L)
    val platform = KitPlatform()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ids = ArrayList<String>()

    init {
        runBlocking {
            names.forEachIndexed { index, name ->
                if (index > 0) switcher.add() as AddResult.Added
                val id = book.list.value.activeId
                switcher.noteSignedIn(SignedInAs(id, host.generation, 100L + index, name, null))
                ids += id
            }
            if (ids.isNotEmpty()) switcher.switchTo(ids.first())
        }
    }

    /** A new flow: what a process started afresh has (nothing unlocked, nothing picked). */
    fun flow(): ProfileFlow = ProfileFlow(switcher, platform, scope)

    fun pin(index: Int, pin: String) = runBlocking { switcher.setPin(ids[index], pin) }

    fun close() {
        scope.cancel()
    }
}
