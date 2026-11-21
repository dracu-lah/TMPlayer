package com.tmplayer.data

import com.tmplayer.platform.Logger
import com.tmplayer.platform.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val TAG = "Accounts"

/**
 * The profile list on the disk: `accounts.json`, written whole through a temporary file and a
 * rename, with the copy before kept as `accounts.json.bak`, so a write cut short by a full disk or
 * a power cut leaves the last good list. A read takes the file, then the copy, then nothing.
 */
class AccountStore(val file: File) {
    private val backup: File get() = File(file.path + ".bak")

    fun read(): AccountList? = sequenceOf(file, backup).firstNotNullOfOrNull { candidate ->
        runCatching {
            candidate.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readText()?.let(AccountCodec::decode)
        }.getOrNull()
    }

    /** False when the disk refused; the list in memory is still the one in use. */
    fun write(list: AccountList): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(AccountCodec.encode(list))
        if (file.isFile) {
            backup.delete()
            if (!file.renameTo(backup)) file.delete()
        }
        temp.renameTo(file) || run {
            file.delete()
            temp.renameTo(file)
        }
    }.getOrElse { error ->
        Logger.w(TAG, "the account list could not be written", error)
        false
    }

    private companion object {
        /** Three profiles take well under a kilobyte; anything this big is not ours. */
        const val MAX_BYTES = 256L * 1024
    }
}

/** Whether a profile's folders can be opened, for the picker: [Recoverable] offers Remove or Sign in again. */
sealed interface ProfileState {
    data object Ready : ProfileState

    data class Recoverable(val reason: Reason) : ProfileState

    enum class Reason {
        /** A profile that had signed in and whose database folder is gone. */
        FolderMissing,

        /** Something that is not a folder where the folder should be. */
        NotAFolder,

        /** The folder is there and cannot be written. */
        ReadOnly,
    }
}

/** Where a profile's folders are, and what shape they are in. */
object AccountDirs {
    /** `<dir>-<folder>`, beside [dir]: `filesDir/tdlib` becomes `filesDir/tdlib-p4k2q9`. */
    fun beside(dir: File, folder: String): File = File(dir.path.trimEnd('/', '\\') + "-" + folder)

    /** [base] as [profile] sees it: the platform's own folders for the adopted profile. */
    fun pathsFor(base: Paths, profile: Profile?): Paths {
        val folder = profile?.folder ?: return base
        val raw = (base as? ProfilePaths)?.base ?: base
        return ProfilePaths(raw, folder)
    }

    fun stateOf(paths: Paths, profile: Profile): ProfileState = runCatching {
        val db = paths.databaseDir
        when {
            db.exists() && !db.isDirectory -> ProfileState.Recoverable(ProfileState.Reason.NotAFolder)
            !db.exists() && profile.signedIn -> ProfileState.Recoverable(ProfileState.Reason.FolderMissing)
            db.exists() && !db.canWrite() -> ProfileState.Recoverable(ProfileState.Reason.ReadOnly)
            else -> ProfileState.Ready
        }
    }.getOrDefault(ProfileState.Recoverable(ProfileState.Reason.FolderMissing))

    /** Makes the database and files folders; false when the disk will not. Downloads wait for the first download. */
    fun make(paths: Paths): Boolean = runCatching {
        listOf(paths.databaseDir, paths.filesDir).all { dir ->
            if (dir.exists() && !dir.isDirectory) dir.delete()
            dir.isDirectory || dir.mkdirs()
        }.also { made ->
            // Marked as it is made: an empty folder is a new database, never one from before markers.
            if (made) DatabaseMarker.ensure(paths.databaseDir)
        }
    }.getOrDefault(false)

    /**
     * Deletes a removed profile's database and files folders, and its downloads folder only when
     * it is empty: downloads are the viewer's files, deleted only when Sign out was asked to.
     */
    fun wipe(paths: Paths) {
        runCatching { deleteTree(paths.databaseDir) }
        runCatching { deleteTree(paths.filesDir) }
        runCatching { paths.downloadsDir.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete() }
    }

    /** Bytes under [dir], counted without following anything outside it; 0 when unreadable. */
    fun bytesUnder(dir: File, limit: Int = 200_000): Long {
        var total = 0L
        var seen = 0
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty() && seen < limit) {
            val next = stack.removeLast()
            val children = runCatching { next.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                seen++
                if (runCatching { child.isDirectory }.getOrDefault(false)) stack.add(child) else total += runCatching { child.length() }.getOrDefault(0L)
            }
        }
        return total
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        val order = ArrayList<File>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            order += next
            if (next.isDirectory) next.listFiles()?.let { stack.addAll(it) }
        }
        for (file in order.asReversed()) file.delete()
    }
}

/**
 * [base] for a profile with folders of its own: each of TDLib's two folders and the downloads one
 * gets its `-<folder>` sibling ([AccountDirs.beside]), on the same volume as the platform's.
 */
class ProfilePaths(val base: Paths, val folder: String) : Paths {
    override val databaseDir: File get() = AccountDirs.beside(base.databaseDir, folder)
    override val filesDir: File get() = AccountDirs.beside(base.filesDir, folder)
    override val downloadsDir: File get() = AccountDirs.beside(base.downloadsDir, folder)
    override fun disk(): DiskInfo = base.disk()
    override fun downloadsDisk(): DiskInfo = base.downloadsDisk()
}

/**
 * The profile list in use, over its [store]: every change is written before it is published, and
 * [onChange] hears each one (the process's [Accounts] points the stores' views at it).
 */
class AccountBook(
    private val store: AccountStore,
    now: Long = System.currentTimeMillis(),
    private val onChange: (AccountList) -> Unit = {},
    /** Removes a removed profile's keys from every store; "" is the adopted profile's plain keys. */
    private val purge: suspend (folder: String) -> Unit = {},
) {
    private val lock = Any()
    private val _list: MutableStateFlow<AccountList>

    init {
        val read = store.read()
        val start = read ?: AccountRules.adopted(now)
        val recovered = AccountRules.recovered(start)
        if (read == null) {
            if (store.file.exists()) Logger.w(TAG, "the account list could not be read; starting with the login on this device's own folders")
        }
        if (read == null || recovered != read) store.write(recovered)
        if (recovered != read && read != null) Logger.i(TAG, "a switch or an add was cut short; reopening ${recovered.activeId}")
        _list = MutableStateFlow(recovered)
        onChange(recovered)
    }

    val list: StateFlow<AccountList> = _list.asStateFlow()

    val active: Profile get() = list.value.active ?: Profile(AccountRules.ADOPTED_ID, null)

    /** Where the small photos are kept, beside the list. */
    val photoDir: File get() = File(store.file.parentFile ?: File("."), "photos")

    /** Changes the list, writes it and publishes it. Returns what is now in use. */
    fun update(transform: (AccountList) -> AccountList): AccountList = synchronized(lock) {
        val before = _list.value
        val after = transform(before)
        if (after == before) return before
        store.write(after)
        _list.value = after
        onChange(after)
        after
    }

    /** Takes the keys of the profile on [folder] out of every store ("" for the adopted profile's plain keys). */
    suspend fun purgeKeys(folder: String) {
        try {
            purge(folder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "a profile's keys could not be cleared", e)
        }
    }

    /**
     * Deletes the folders in the trash over [base]'s folders and the profile's keys in every store
     * ([purge]), then forgets them. Never a folder in use: the open profile's, or the one a switch
     * is opening.
     */
    suspend fun sweep(base: Paths) {
        val list = _list.value
        val inUse = setOfNotNull(list.active?.folder ?: "", list.switchingTo?.let { id -> list.byId(id)?.folder ?: "" })
        val done = list.trash.filter { it !in inUse }
        if (done.isEmpty()) return
        for (folder in done) {
            val profile = Profile(id = "trash", folder = folder.ifEmpty { null })
            AccountDirs.wipe(AccountDirs.pathsFor(base, profile))
            try {
                purge(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a removed profile's keys could not be cleared", e)
            }
            runCatching { File(photoDir, "${folder.ifEmpty { AccountRules.ADOPTED_ID }}.jpg").delete() }
            Logger.i(TAG, "deleted the folders and keys of a removed profile")
        }
        update { it.copy(trash = it.trash - done.toSet()) }
    }
}

/**
 * The process's profiles (HX88). [open] is called once by each platform before the first store
 * or `Td.start`; until then, and in a build or test that never calls it, there is one profile on
 * the platform's own folders and every store reads as it always has.
 */
object Accounts {
    const val FILE_NAME = "accounts.json"

    private val folder = MutableStateFlow<String?>(null)

    /** The open profile's [Profile.folder], null for the platform's own folders. The stores' views follow it. */
    val activeFolder: StateFlow<String?> = folder.asStateFlow()

    @Volatile
    private var current = AccountStamp(0L, null)

    /**
     * The open profile and how many switches came before it. Whatever keeps one profile's data (a
     * snapshot, a listing, an answer on its way) is tagged with the stamp it was made under, dropped
     * once the stamp has changed, and written to the file of its own stamp ([scopedFile]), never to
     * the one open at the moment of writing.
     */
    val stamp: AccountStamp get() = current

    private val listeners = ArrayList<() -> Unit>()
    private val purgers = ArrayList<suspend (String) -> Unit>()

    @Volatile
    private var scope: CoroutineScope = newScope(current)

    /**
     * Where work of the open profile runs: cancelled on every switch, and carrying the profile's
     * [AccountOwner], so a store refuses whatever such work still tries to write once another
     * profile is open.
     */
    val profileScope: CoroutineScope get() = scope

    @Volatile
    var book: AccountBook? = null
        private set

    /** Opens the list in [dir] (once; later calls return the same book). */
    fun open(dir: File): AccountBook = book ?: synchronized(this) {
        book ?: AccountBook(AccountStore(File(dir, FILE_NAME)), onChange = ::publish, purge = ::purge).also { book = it }
    }

    /** The open profile, or the adopted one before [open]. */
    val active: Profile get() = book?.active ?: Profile(AccountRules.ADOPTED_ID, null)

    /** [file] as the profile of [stamp] keeps it: `home-cache.json` becomes `home-cache-<folder>.json`. */
    fun scopedFile(file: File, stamp: AccountStamp = current): File = scopedFile(file, stamp.folder)

    fun scopedFile(file: File, folder: String?): File {
        val name = folder ?: return file
        val stem = file.name.substringBeforeLast('.', file.name)
        val ext = file.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == file.name) "" else ".$it" }
        return File(file.parentFile, "$stem-$name$ext")
    }

    /** Runs [block] after every change of the open profile, for what keeps one profile's data in memory. */
    fun onSwitch(block: () -> Unit) {
        synchronized(listeners) { listeners += block }
    }

    /** A store that keeps account keys ([AccountScopedDataStore.purge]): cleared of each removed profile's. */
    fun onPurge(block: suspend (folder: String) -> Unit) {
        synchronized(purgers) { purgers += block }
    }

    @Volatile
    private var registered = CompletableDeferred<Unit>()

    /**
     * Each platform calls this once what keeps account data outside the stores is in place
     * (`HomeCache.file`, `PageQueries.store`) and the preference stores are open, so their purgers
     * are registered ([onPurge]). The first sweep of a start waits for it ([sweepOnceRegistered]):
     * a sweep before it took a removed profile's folders and forgot it, leaving its cache files
     * and keys behind (HX88 review, LOW 1).
     */
    fun storesRegistered() {
        registered.complete(Unit)
    }

    /**
     * The first sweep of a start: once [storesRegistered] was called, or after [waitMs] for a
     * platform that never calls it (the sweep is then as late as it can safely be, never skipped).
     */
    suspend fun sweepOnceRegistered(book: AccountBook, paths: Paths, waitMs: Long = REGISTER_WAIT_MS) {
        withTimeoutOrNull(waitMs) { registered.await() }
        book.sweep(paths)
    }

    /** How long the first sweep waits for [storesRegistered]. */
    const val REGISTER_WAIT_MS = 10_000L

    /** Every purger ([onPurge]) for the profile on [folder]; the book's sweep calls it. */
    internal suspend fun purge(folder: String) {
        for (each in synchronized(purgers) { purgers.toList() }) each(folder)
    }

    private fun newScope(stamp: AccountStamp) = CoroutineScope(SupervisorJob() + Dispatchers.Default + AccountOwner(stamp))

    /** The stamp moves first, then the stores' folder, then the old profile's work is cancelled and the listeners hear it. */
    private fun publish(list: AccountList) {
        val next = list.active?.folder
        // The value of the lock's block, not a val set inside it: Kotlin/Native's lock has no
        // contract that says the block runs once (the iOS build, B2).
        val before: CoroutineScope = synchronized(this) {
            if (current.folder == next && folder.value == next) return
            val was = scope
            current = AccountStamp(current.generation + 1, next)
            scope = newScope(current)
            was
        }

        folder.value = next
        before.cancel()
        val each = synchronized(listeners) { listeners.toList() }
        for (listener in each) runCatching(listener)
    }

    /** For tests: point the process at [book]'s open profile, as a switch would. */
    internal fun resetForTest(book: AccountBook? = null) {
        synchronized(this) {
            this.book = book
            registered = CompletableDeferred()
        }
        publish(book?.list?.value ?: AccountRules.adopted(0L))
    }
}

/** Whether [this] is still the open profile's turn. */
val AccountStamp.isCurrent: Boolean get() = Accounts.stamp == this

/**
 * The profile a coroutine works for ([Accounts.profileScope] carries it). A write to an account
 * store from a coroutine whose owner is no longer the open profile is dropped
 * ([AccountScopedDataStore]); one without an owner writes for the profile open when it asks.
 */
class AccountOwner(val stamp: AccountStamp) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountOwner>

    override fun toString(): String = "AccountOwner(${stamp.generation})"
}
