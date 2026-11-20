package com.tmplayer.data

import com.tmplayer.platform.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.Collections

/** A platform's folders under one temporary directory, as Android lays them out. */
class TempPaths(val root: File = Files.createTempDirectory("accounts").toFile()) : Paths {
    override val databaseDir: File get() = File(root, "tdlib")
    override val filesDir: File get() = File(root, "cache")
    override val downloadsDir: File get() = File(root, "downloads")

    init {
        // The login already on the device: TDLib's folder is there.
        databaseDir.mkdirs()
    }

    @Volatile
    var free: Long = 50L * 1024 * 1024 * 1024
    override fun disk(): DiskInfo = DiskInfo(free, 100L * 1024 * 1024 * 1024)
}

/**
 * The one TDLib client as [AccountSwitcher] sees it. A close takes [closeMs] and the next client
 * opens only after it, as Td's client loop does; [overlaps] counts every reopen asked for while the
 * last client was still closing, which would be two clients at once.
 */
class FakeHost(@Volatile var closeMs: Long = 0L) : ClientHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var openOn: String = "tdlib"
    val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var overlaps = 0

    @Volatile
    var logOuts = 0

    /** The live client's generation: a new one for each client opened, and after each log out. */
    @Volatile
    var generation = 1L

    /** Whether the old client is still between its close and the next client. */
    @Volatile
    var closing = false
    private var last: Deferred<Unit>? = null
    val signIns = MutableSharedFlow<SignedInAs>(extraBufferCapacity = 8)

    override suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean {
        val folder = paths.databaseDir.name
        val job = synchronized(this) {
            if (last?.isCompleted == false) overlaps++
            closing = true
            val wait = closeMs
            scope.async {
                delay(wait)
                // As Td's client loop: the old client has let go, the stores move, then the next client.
                onClosed()
                // TDLib makes its database folder as it opens.
                paths.databaseDir.mkdirs()
                synchronized(this@FakeHost) {
                    closing = false
                    generation++
                    openOn = folder
                    opened += folder
                }
            }.also { last = it }
        }
        return withTimeoutOrNull(timeoutMs) { job.await() } != null
    }

    override suspend fun awaitOpen() {
        synchronized(this) { last }?.await()
    }

    override suspend fun endSession(timeoutMs: Long): Boolean {
        logOuts++
        generation++
        return true
    }

    override fun sessionNow(): Long? = generation

    /** Who signed in, as the live client would say it of the profile open now. */
    fun me(book: AccountBook, telegramId: Long, name: String, photo: ByteArray? = null) =
        SignedInAs(book.list.value.activeId, generation, telegramId, name, photo)

    override val signedIn: Flow<SignedInAs> = signIns
}

/** A book over a fresh `accounts.json` in [dir], heard by nobody else. */
fun testBook(dir: File, now: Long = 1_000L): AccountBook = AccountBook(AccountStore(File(dir, Accounts.FILE_NAME)), now)
