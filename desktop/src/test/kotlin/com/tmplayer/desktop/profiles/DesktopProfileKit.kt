package com.tmplayer.desktop.profiles

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
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * The desktop's folders under one temporary directory, laid out as the desktop lays them out: the
 * session beside the settings, the cache and the downloads apart (DesktopPaths).
 */
internal class DesktopKitPaths(val root: File = Files.createTempDirectory("desktop-profiles").toFile()) : Paths {
    override val databaseDir: File get() = File(root, "data/tdlib")
    override val filesDir: File get() = File(root, "cache/tdlib-cache").apply { mkdirs() }
    override val downloadsDir: File get() = File(root, "Downloads/TMPlayer")
    override fun disk(): DiskInfo = DiskInfo(50L shl 30, 100L shl 30)

    init {
        databaseDir.mkdirs()
    }
}

/** One TDLib client, at once: a close is over as soon as it is asked for. */
internal class DesktopKitHost : ClientHost {
    @Volatile var generation = 1L

    override suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean {
        onClosed()
        paths.databaseDir.mkdirs()
        generation++
        return true
    }

    override suspend fun awaitOpen() = Unit

    override suspend fun endSession(timeoutMs: Long): Boolean {
        generation++
        return true
    }

    override fun sessionNow(): Long? = generation

    override val signedIn: Flow<SignedInAs> = emptyFlow()
}

/** What the platform does, recorded; nothing plays and nothing downloads. */
internal class DesktopKitPlatform : ProfilePlatform {
    val wiped = java.util.Collections.synchronizedList(mutableListOf<String>())
    val said = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun playing() = false
    override fun downloading() = false
    override suspend fun beforeSwitch(onWaiting: (List<String>) -> Unit) = Unit
    override suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean) {
        wiped += profile.id
    }
    override fun say(text: String) {
        said += text
    }
    /** The app lock's PIN for this test, or null for none (the default). */
    @Volatile var appPin: String? = null
    override val appLock: Flow<AppLockSettings?> get() = flowOf(AppLockSettings(pinSet = appPin != null))
    override fun appLockNow(): AppLockSettings = AppLockSettings(pinSet = appPin != null)
    override fun appLockChecker(): com.tmplayer.ui.lock.PinChecker? = object : com.tmplayer.ui.lock.PinChecker {
        override suspend fun waitUntil(): Long = 0L
        override suspend fun check(pin: String): com.tmplayer.data.PinCheck =
            if (pin == appPin) com.tmplayer.data.PinCheck.Right else com.tmplayer.data.PinCheck.Wrong(com.tmplayer.data.PinAttempts(1))
        override fun freeLeft(attempts: com.tmplayer.data.PinAttempts): Int = 3
    }
}

/**
 * Profiles over a fake client, signed in as [names] (the first is the profile adopted in place),
 * in a book of their own: the process's [Accounts] is never touched, so the other desktop tests
 * in the same JVM keep the one profile they expect.
 */
internal class DesktopProfileKit(vararg names: String) {
    val dir: File = Files.createTempDirectory("desktop-profile-kit").toFile()
    val base = DesktopKitPaths()
    val host = DesktopKitHost()
    val book = AccountBook(AccountStore(File(dir, Accounts.FILE_NAME)))
    val switcher = AccountSwitcher(book, host, base = { base }, closeTimeoutMs = 2_000L)
    val platform = DesktopKitPlatform()
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
