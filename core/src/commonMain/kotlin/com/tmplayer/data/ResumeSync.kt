package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.LinkPreviewOptions
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageSendOptions
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Resume on other devices (U7), opt in (Settings, [SettingsStore.resumeSync]): every device signed
 * in to the same account with the switch on keeps one small file ([SyncDocument]) in the account's
 * own Saved Messages, so the TV picks up where the phone stopped and a video watched on one is
 * ticked on the other. No server: the file is the viewer's own, in their own Telegram.
 *
 * A sync finds the file by its caption ([FILE_TAG]), reads it, merges it with this device's lists
 * ([SyncDocuments.merge]), takes in what is newer ([SyncDocuments.apply]) and puts the merge back
 * in the same message (editMessageMedia), so messages never pile up; a second file left by two
 * devices starting at once is merged in and deleted. A file that cannot be read, or that a newer
 * TMPlayer wrote, is left alone and this device's lists are kept.
 *
 * It runs at start ([syncSoon]), when a video opens ([pullFor], briefly, never holding playback)
 * and a little after the player saves a place ([pushSoon]), at most once in [MIN_INTERVAL_MS] on
 * its own, and with Save mobile data on not over a metered line unless the viewer asks ("Sync
 * now"). Every call that meets a FLOOD_WAIT waits it out itself, up to [MAX_FLOOD_WAIT_S], a few
 * times, then gives up until the next sync: nothing retries on its own.
 *
 * Older TMPlayers kept the places in one text message ([ResumeSyncDoc]). While that message is
 * there it is read and kept current with the places, so a device not updated yet still resumes;
 * it is never sent anew, and deleting the sync file deletes it too.
 */
object ResumeSync {

    private val lock = Mutex()
    /** When each profile's sync last ran in this process (HX88): one profile's run never makes another wait. */
    private val lastAutoAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun lastAutoAtNow(): Long = lastAutoAt[Accounts.stamp.folder.orEmpty()] ?: 0L
    @Volatile private var lastPullAt = 0L
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastSendChat = 0L

    /** Marks this process asked Telegram about without finding the message, not asked again. */
    private val healTried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    @Volatile private var watchedStore: WatchedStore? = null
    @Volatile private var bookmarkStore: BookmarkStore? = null
    @Volatile private var connectivity: Connectivity? = null
    @Volatile private var television = false
    @Volatile private var appVersion = ""

    private val _running = MutableStateFlow(false)

    /** True while a sync runs, for the Settings row's "Syncing" line. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Called once at start by each app: the watched store this process shares, the line's metered
     * state where the platform has one, and whether this is a television (where Save mobile data
     * holds nothing back, [MeteredPolicy.saveMobileDataInForce]).
     */
    fun install(
        watched: WatchedStore,
        connectivity: Connectivity?,
        television: Boolean,
        appVersion: String,
        bookmarks: BookmarkStore? = null,
        /** A device short of memory: nothing runs by itself while the app is in the background. */
        lowEnd: Boolean = false,
    ) {
        watchedStore = watched
        bookmarkStore = bookmarks
        this.lowEnd = lowEnd
        this.connectivity = connectivity
        this.television = television
        this.appVersion = appVersion
    }

    /** What a sync did, for the Settings row. */
    sealed interface Outcome {
        data object Off : Outcome
        data object NoConnection : Outcome
        data object WaitingForUnmetered : Outcome
        data object WaitingForFile : Outcome
        data object OtherAccount : Outcome
        data class TooNew(val version: Int) : Outcome
        data object Unreadable : Outcome
        data class Done(val received: Int, val wrote: Boolean, val dropped: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * A sync in the background, soon: after sign in, at start, and when a list changed. [afterMs]
     * 0 is the viewer's own "turn on": at once. The rest is [SyncScheduler]'s: at most once in
     * [MIN_INTERVAL_MS], and nothing in the background on a low end device.
     */
    fun syncSoon(settings: SettingsStore, afterMs: Long = START_DELAY_MS) =
        scheduler(settings).ask(if (afterMs <= 0) SyncScheduler.Why.TurnedOn else SyncScheduler.Why.Start)

    /** After the player saved a place or a list changed: written once the saves have settled. */
    fun pushSoon(settings: SettingsStore) = scheduler(settings).ask(SyncScheduler.Why.Settled)

    /** The player is closing: this place goes out soon, at most twice a minute. */
    fun leavingPlayer(settings: SettingsStore) = scheduler(settings).ask(SyncScheduler.Why.LeftPlayer)

    /** The app is in front ([visible]) or not; a sync held in the background on a low end device goes on when it is. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) scheduler?.foregrounded()
    }

    /** How long a sync waits for Telegram's line before it says "waiting for a connection"; a test shortens it. */
    @Volatile var connectWaitMs: Long = 30_000L

    @Volatile private var scheduler: SyncScheduler? = null
    @Volatile private var foreground = true
    @Volatile private var lowEnd = false

    private fun scheduler(settings: SettingsStore): SyncScheduler = scheduler ?: synchronized(this) {
        scheduler ?: SyncScheduler(
            scope = Background.scope,
            clock = System::currentTimeMillis,
            foreground = { foreground },
            lowEnd = lowEnd,
            lastRunAt = { lastAutoAtNow() },
            // Three failed runs in a row (WP-97, WP-100): what the viewer did not ask for is left alone.
            stopped = { stoppedByFailures(settings) },
            // As the profile open when the run starts (HX88): when it ran is that profile's, kept
            // here and in its store, or nowhere once another profile is open.
            job = { why ->
                val owner = Accounts.stamp
                withContext(AccountOwner(owner)) {
                    val ran = sync(settings, manual = why == SyncScheduler.Why.Manual) != Outcome.Off
                    if (ran) {
                        val now = System.currentTimeMillis()
                        lastAutoAt[owner.folder.orEmpty()] = now
                        runCatching { settings.setResumeSyncWroteAt(now) }
                    }
                    ran
                }
            },
        ).also {
            scheduler = it
            // Where each profile's last run stood before this process: a restart, or a switch to
            // it, does not start its pause over.
            fun load() {
                val owner = Accounts.stamp
                Background.scope.launch(AccountOwner(owner)) {
                    val stored = runCatching { settings.resumeSyncWroteAtNow() }.getOrDefault(0L)
                    val key = owner.folder.orEmpty()
                    lastAutoAt[key] = maxOf(lastAutoAt[key] ?: 0L, stored)
                }
            }
            load()
            Accounts.onSwitch { load() }
        }
    }

    /**
     * Before a video opens: the other devices' places taken in, so a video half watched on the
     * phone opens at that point on the TV. Waits at most [PULL_TIMEOUT_MS], at most once a minute;
     * never blocks playback.
     */
    suspend fun pullFor(settings: SettingsStore) {
        if (!runCatching { settings.resumeSyncNow() }.getOrDefault(false)) return
        if (stoppedByFailures(settings)) return
        val now = System.currentTimeMillis()
        if (now - lastPullAt < MIN_PULL_INTERVAL_MS) return
        lastPullAt = now
        // runCatching goes outside the timeout: inside it would swallow the timeout's own cancellation.
        runCatching { withTimeoutOrNull(PULL_TIMEOUT_MS) { sync(settings, push = false) } }
    }

    /**
     * Read, merge, take in, write back. [manual] is "Sync now": it runs over a metered line too.
     * [replace] writes this device's lists over a file that could not be read, which only the
     * viewer asks for.
     */
    suspend fun sync(
        settings: SettingsStore,
        push: Boolean = true,
        manual: Boolean = false,
        replace: Boolean = false,
    ): Outcome {
        // The profile this run is for (HX88): taken when it is asked, before it waits for the lock.
        // Every store write of the run is that profile's, and dropped once another is open, so a
        // switch part way never puts one account's lists into another's stores or file.
        val owner = currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        return lock.withLock {
            // Not for a profile no longer open, nor while a switch is under way.
            if (!OwnedSessions.mayAct(owner)) return@withLock Outcome.Off
            withContext(Dispatchers.IO + AccountOwner(owner)) {
                _running.value = true
                current = currentCoroutineContext()[Job]
                try {
                    val outcome = run(settings, push, manual, replace)
                    if (outcome != Outcome.Off) runCatching { settings.setResumeSyncStatus(withStreak(settings, statusOf(outcome))) }
                    outcome
                } finally {
                    current = null
                    _running.value = false
                }
            }
        }
    }

    /** [status] with the count of failed runs in a row ([SyncStreak]) worked out from the one stored before it. */
    private suspend fun withStreak(settings: SettingsStore, status: SyncStatus): SyncStatus {
        val before = runCatching { settings.resumeSyncStatus.first() }.getOrNull()
        return status.copy(failures = SyncStreak.next(before, status))
    }

    /** True when the last runs failed [SyncStreak.STOP_AFTER] times in a row: nothing runs by itself until the viewer asks. */
    private suspend fun stoppedByFailures(settings: SettingsStore): Boolean =
        SyncStreak.stopped(runCatching { settings.resumeSyncStatus.first() }.getOrNull())

    /** The run under way, for a switch to stop and wait for. */
    @Volatile private var current: Job? = null

    /**
     * Before a switch (HX88): the run under way is cancelled where it can be and waited for, up to
     * [SWITCH_WAIT_MS]; past that the switch goes on, and the run's later writes are dropped (they
     * carry their owner), so a late finish is harmless.
     */
    private val stopBeforeSwitch: suspend () -> Unit = {
        current?.let { job ->
            job.cancel()
            withTimeoutOrNull(SWITCH_WAIT_MS) { job.join() }
        }
    }

    /** How long a switch waits for a sync to stop. */
    const val SWITCH_WAIT_MS = 20_000L

    init {
        BeforeSwitch.add(stopBeforeSwitch) { if (current != null) com.tmplayer.i18n.L.profilesWaitingSync else null }
    }

    /**
     * Moves where the sync file is kept (HX87): a private channel the viewer owns, or Saved
     * Messages for null. It waits for a sync that is running (the file it is writing is finished,
     * never half), then drops what was remembered about the old place; the old copy is left where
     * it is and its place is returned for the viewer to be told. The next sync fills the new
     * place: it reads what is there first, then merges, so a channel already holding a file from
     * another device loses nothing.
     */
    suspend fun changeDestination(settings: SettingsStore, choice: SyncChannelChoice?): String? {
        val owner = currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        return lock.withLock { withContext(AccountOwner(owner)) { SyncDestinations.applyChoice(settings, choice) } }
    }

    /**
     * Turns the sync off here and deletes the file (and an older TMPlayer's message) from Saved
     * Messages, and from the chosen channel too when there is one. This device's lists stay. True
     * when there was nothing left to delete afterwards.
     */
    suspend fun turnOffAndDelete(settings: SettingsStore): Boolean {
        val owner = currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        return turnOffAndDelete(settings, owner)
    }

    private suspend fun turnOffAndDelete(settings: SettingsStore, owner: AccountStamp): Boolean = lock.withLock {
        withContext(Dispatchers.IO + AccountOwner(owner)) {
          current = currentCoroutineContext()[Job]
          try {
            val known = runCatching { settings.resumeSyncFileNow() }.getOrDefault(0L)
            val knownFileChat = runCatching { settings.syncFileChatNow() }.getOrDefault(0L)
            val knownLegacy = runCatching { settings.resumeSyncMessageNow() }.getOrDefault(0L)
            val choice = runCatching { settings.syncChannelNow() }.getOrNull()
            settings.setResumeSync(false)
            settings.setSyncOldCopy("")
            val session = Td.sessionFor(owner) ?: return@withContext false
            try {
                val td = session.client
                val myId = Td.myId().takeIf { it != 0L } ?: return@withContext false
                val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
                var clean = true
                // Saved Messages first, then the chosen channel: a copy may be in either, or both.
                val chats = listOfNotNull(saved, choice?.takeIf { it.account == myId }?.chatId)
                for (chat in chats) {
                    val hint = if (knownFileChat == chat || (knownFileChat == 0L && chat == saved)) known else 0L
                    val ids = findFiles(td, chat, hint).map { it.id }.toMutableSet()
                    if (chat == saved) findLegacy(td, chat, knownLegacy)?.let { ids += it.id }
                    if (ids.isEmpty()) continue
                    if (waited { td.deleteMessages(chat, ids.toLongArray(), true) } !is TdlResult.Success) clean = false
                }
                return@withContext clean
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Deleting the sync file failed", e)
                false
            }
          } finally {
            current = null
          }
        }
    }

    private suspend fun run(settings: SettingsStore, push: Boolean, manual: Boolean, replace: Boolean): Outcome {
        if (!settings.resumeSyncNow()) return Outcome.Off
        val watched = watchedStore ?: return Outcome.Failed("not ready")
        val bookmarks = bookmarkStore
        if (!manual && holdForLine(settings)) return Outcome.WaitingForUnmetered
        // The owner's own client, never the next profile's that came up while this waited.
        val runOwner = currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        val session = Td.sessionFor(runOwner, connectWaitMs) ?: return Outcome.NoConnection
        val td = session.client
        return try {
            val myId = Td.myId().takeIf { it != 0L } ?: return Outcome.NoConnection
            val owner = settings.resumeSyncAccountNow()
            if (owner != 0L && owner != myId) {
                // Never one account's lists into another's file: off until turned on again here.
                settings.setResumeSync(false)
                return Outcome.OtherAccount
            }
            val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
            // Where the file lives: the viewer's private channel only while it is provably theirs alone,
            // else Saved Messages (HX87). A channel that could not be checked: nothing is done this time.
            val place = SyncDestinations.resolve(settings, myId, saved, TdChannelProbe(td))
            if (place is SyncPlace.Unreachable) return Outcome.Failed(place.reason)
            val chat = place.chatId
            val inChannel = place is SyncPlace.Channel
            val now = System.currentTimeMillis()
            val budget = Budget(LOOKUP_BUDGET)
            val repository = ChatRepository(td)
            val titles = HashMap<Long, String>()
            suspend fun chatTitle(chatId: Long): String =
                titles.getOrPut(chatId) { td.getChat(chatId).valueOrNull?.title.orEmpty() }

            // The older TMPlayer's message first, so its places are here before this device's
            // lists are read.
            val legacy = if (inChannel) null else findLegacy(td, chat, settings.resumeSyncMessageNow())
            var received = legacy?.let { takeInLegacy(it, settings, repository, budget, ::chatTitle) } ?: 0

            // The file: every copy found, read and merged; nothing is written over one not read.
            val files = findFiles(td, chat, knownFileFor(settings, chat, saved))
            var remote = SyncDocument(account = myId)
            var unreadable = false
            var newest = 0
            for (message in files) {
                when (val read = download(td, message)) {
                    is Download.Failed -> return Outcome.Failed(read.reason)
                    is Download.Bytes -> when (val doc = SyncDocuments.decode(read.bytes, myId)) {
                        is SyncRead.Ok -> remote = SyncDocuments.merge(remote, doc.doc, now)
                        is SyncRead.TooNew -> newest = maxOf(newest, doc.version)
                        is SyncRead.OtherAccount, SyncRead.NotASyncFile -> unreadable = true
                    }
                    Download.TooLarge -> unreadable = true
                }
            }
            if (newest > 0) return Outcome.TooNew(newest)
            if (unreadable && !replace) return Outcome.Unreadable

            val base = settings.resumeSyncBaseNow()
            val local = SyncDocuments.collect(
                settings = settings.rawEntries(),
                watched = watched.rawEntries(),
                base = base,
                remoteFavourites = remote.favourites,
                account = myId,
                now = now,
                appVersion = appVersion,
                bookmarks = bookmarks?.rawEntries().orEmpty(),
            )
            val (merged, cappedOut) = SyncDocuments.mergeCounting(remote, local, now)
            val apply = SyncDocuments.apply(local, merged)

            // In: the places through Telegram, so each has this device's own file id; the newest
            // first, as many as the budget allows, the rest at the next sync.
            val imported = HashSet<String>()
            for (record in apply.resumeIn.sortedByDescending { it.updatedAt }) {
                if (!budget.take()) break
                val found = repository.lookupMedia(record.chatId, record.messageId) as? MessageLookup.Found ?: continue
                if (settings.importSyncedResume(record, found.item.fileId, found.item.title, chatTitle(record.chatId))) {
                    imported += ProgressKeys.of(record.chatId, record.messageId)
                }
            }
            for ((key, at) in apply.resumeOut) settings.dropSyncedResume(key, at)
            val listChanges = watched.applySync(apply) + watched.lists.mergeIn(merged.lists) +
                (bookmarks?.applySync(apply.bookmarksIn, apply.bookmarksOut) ?: 0) +
                settings.applySyncedCorrections(apply.correctionsIn, apply.correctionsOut)
            settings.applySyncedFavourites(apply.favouritesIn, apply.favouritesOut)
            received += imported.size + listChanges + apply.favouritesIn.size + apply.favouritesOut.size
            heal(watched, repository, budget, ::chatTitle)

            // What changed here because of the file, and nothing else, until the push below goes
            // through: a removal made here since the last push still has to be pushed.
            settings.setResumeSyncBase(
                base.copy(
                    resumeKeys = base.resumeKeys - apply.resumeOut.keys + imported,
                    favourites = base.favourites - apply.favouritesOut + apply.favouritesIn,
                ),
            )
            if (!push) return Outcome.Done(received, wrote = false, dropped = 0)

            val encoded = SyncDocuments.encode(merged.copy(account = myId, updatedAt = now, appVersion = appVersion))
            val changed = files.isEmpty() || unreadable || files.size > 1 || !sameLists(remote, encoded.doc)
            var wrote = false
            if (changed) {
                // The channel is checked again right before the write: the reads and the merge took time,
                // and nobody may have joined since. Not safe or not known: nothing is written.
                if (inChannel) SyncDestinations.verifyBeforeWrite(settings, TdChannelProbe(td), chat)?.let { return Outcome.Failed(it) }
                when (val write = write(td, chat, files.firstOrNull(), encoded.bytes, settings, pin = !inChannel)) {
                    WriteResult.Written -> wrote = true
                    WriteResult.WaitingForFile -> return Outcome.WaitingForFile
                    is WriteResult.Failed -> {
                        // Telegram refused the account a post in the channel: Saved Messages for the next hour.
                        if (inChannel && write.refused) SyncDestinations.notePostingRefused(settings)
                        return Outcome.Failed(if (inChannel && write.refused) SyncChannelBlock.PostingBlocked().words() else write.reason)
                    }
                }
                // Every other copy is in the file now: gone, so one message stays.
                val extra = files.drop(1).map { it.id }
                if (extra.isNotEmpty()) waited { td.deleteMessages(chat, extra.toLongArray(), true) }
            }
            legacy?.let { keepLegacyCurrent(td, chat, it, settings, base, now) }
            // What this device held as the file was read, as changed by the file: a removal made
            // here while the sync ran is still told at the next one.
            settings.setResumeSyncBase(
                SyncBase(
                    resumeKeys = local.resume.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) } - apply.resumeOut.keys + imported,
                    favourites = local.favourites.keys - apply.favouritesOut + apply.favouritesIn,
                    at = now,
                ),
            )
            settings.setResumeSyncAccount(myId)
            Outcome.Done(received, wrote, dropped = cappedOut.total + encoded.dropped.total)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Resume sync failed", e)
            Outcome.Failed(Failures.humanise(e))
        }
    }

    /** Save mobile data on (where it applies) and the line metered: automatic syncs wait. */
    private suspend fun holdForLine(settings: SettingsStore): Boolean {
        val metered = connectivity?.metered?.value ?: return false
        return metered && MeteredPolicy.saveMobileDataInForce(settings.saveMobileDataNow(), television)
    }

    /** The lists of two files alike, whoever wrote them and when. */
    private fun sameLists(a: SyncDocument, b: SyncDocument): Boolean =
        a.copy(account = 0, updatedAt = 0, appVersion = "") == b.copy(account = 0, updatedAt = 0, appVersion = "")

    fun statusOf(outcome: Outcome, now: Long = System.currentTimeMillis()): SyncStatus = when (outcome) {
        is Outcome.Done -> SyncStatus(SyncStatus.Kind.Done, now, outcome.received, outcome.dropped)
        Outcome.NoConnection, Outcome.Off -> SyncStatus(SyncStatus.Kind.NoConnection, now)
        Outcome.WaitingForUnmetered -> SyncStatus(SyncStatus.Kind.WaitingForUnmetered, now)
        Outcome.WaitingForFile -> SyncStatus(SyncStatus.Kind.WaitingForFile, now)
        Outcome.OtherAccount -> SyncStatus(SyncStatus.Kind.OtherAccount, now)
        is Outcome.TooNew -> SyncStatus(SyncStatus.Kind.TooNew, now)
        Outcome.Unreadable -> SyncStatus(SyncStatus.Kind.Unreadable, now)
        is Outcome.Failed -> SyncStatus(SyncStatus.Kind.Failed, now, detail = outcome.reason)
    }

    // ---- the file ------------------------------------------------------------------------------

    /**
     * The remembered file message, when it is in [chat]: a sync file id belongs to one chat, so
     * after the destination moved (or fell back to Saved Messages for a while) it is not used
     * there. A device from before HX87 remembers no chat, and its file is in Saved Messages.
     */
    private suspend fun knownFileFor(settings: SettingsStore, chat: Long, saved: Long): Long {
        val at = settings.syncFileChatNow()
        return if (at == chat || (at == 0L && chat == saved)) settings.resumeSyncFileNow() else 0L
    }

    /** Every sync file in Saved Messages: the one remembered, by its caption, pinned, or recent. Newest first. */
    private suspend fun findFiles(td: TdlClient, chatId: Long, known: Long): List<Message> {
        val found = LinkedHashMap<Long, Message>()
        fun keep(message: Message?) {
            if (message != null && isFile(message) && message.sendingState == null) found[message.id] = message
        }
        if (known != 0L) keep(waited { td.getMessage(chatId, known) }.valueOrNull)
        waited { td.searchChatMessages(chatId, null, FILE_TAG, null, 0, 0, SEARCH_LIMIT, SearchMessagesFilterDocument()) }
            .valueOrNull?.messages?.forEach { keep(it) }
        keep(td.getChatPinnedMessage(chatId).valueOrNull)
        if (found.isEmpty()) {
            // Not indexed yet, or search not answering: the newest messages, from TDLib's own copy.
            td.getChatHistory(chatId, 0, 0, HISTORY_LIMIT, false).valueOrNull?.messages?.forEach { keep(it) }
        }
        return found.values.sortedWith(compareByDescending<Message> { it.date }.thenByDescending { it.id })
    }

    private fun isFile(message: Message): Boolean {
        val content = message.content as? MessageDocument ?: return false
        return content.caption?.text?.trimStart()?.startsWith(FILE_TAG) == true &&
            content.document?.fileName.orEmpty().startsWith(FILE_STEM)
    }

    private sealed interface Download {
        class Bytes(val bytes: ByteArray) : Download
        data object TooLarge : Download
        class Failed(val reason: String) : Download
    }

    private suspend fun download(td: TdlClient, message: Message): Download {
        val file = (message.content as? MessageDocument)?.document?.document ?: return Download.TooLarge
        if (file.size > SyncDocuments.MAX_READ_BYTES || file.expectedSize > SyncDocuments.MAX_READ_BYTES) return Download.TooLarge
        if (file.local?.isDownloadingCompleted != true) {
            val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { waited { td.downloadFile(file.id, 1, 0, 0, true) } }
                ?: return Download.Failed(L.settingsResumeSyncOffline)
            if (result !is TdlResult.Success) return Download.Failed(Failures.humanise(result.errorMessage ?: "download"))
        }
        val data = waited { td.readFilePart(file.id, 0, 0) }
        val bytes = data.valueOrNull?.data ?: return Download.Failed(Failures.humanise(data.errorMessage ?: "read"))
        return Download.Bytes(bytes)
    }

    private sealed interface WriteResult {
        data object Written : WriteResult
        data object WaitingForFile : WriteResult

        /** [refused]: Telegram's own word that this account may not post in that chat. */
        class Failed(val reason: String, val refused: Boolean = false) : WriteResult
    }

    /**
     * The file put in place: [target] edited when there is one, else sent once and pinned. A
     * file sent within [RESEND_GUARD_MS] and not confirmed yet is waited for, never sent twice.
     */
    private suspend fun write(
        td: TdlClient,
        chatId: Long,
        target: Message?,
        bytes: ByteArray,
        settings: SettingsStore,
        pin: Boolean = true,
    ): WriteResult {
        val dir = uploadDir() ?: return WriteResult.Failed("no folder")
        sweep(dir)
        // Nothing half written: the copy for the upload needs room first, and a full disk is said so.
        if (!hasRoomFor(dir, bytes.size.toLong())) return WriteResult.Failed(L.syncchannelNoSpace)
        val now = System.currentTimeMillis()
        val folder = File(dir, now.toString()).apply { mkdirs() }
        val local = File(folder, "$FILE_STEM.json.gz")
        local.writeBytes(bytes)
        val caption = FormattedText("$FILE_TAG\n${L.settingsResumeSyncCaption}", emptyArray())
        val content = InputMessageDocument(InputDocument(InputFileLocal(local.absolutePath), null, true), caption)
        if (target != null) {
            val edited = waited { td.editMessageMedia(chatId, target.id, null, content) }
            if (edited is TdlResult.Success) {
                settings.setResumeSyncFile(edited.result.id)
                settings.setSyncFileChat(chatId)
                return WriteResult.Written
            }
            // Gone since it was read (deleted by the viewer): sent anew below. Anything else is tried next time.
            // Only Telegram's own "no such message" counts as gone: an outage or a flood wait on this
            // lookup too would otherwise send a second sync file.
            val gone = when (val answer = td.getMessage(chatId, target.id)) {
                is TdlResult.Success -> false
                is TdlResult.Failure -> StaleEntries.classify(answer.code, answer.message) is MessageLookup.Gone
            }
            if (!gone) return WriteResult.Failed(Failures.humanise(edited.errorMessage ?: "edit"), SyncChannelCheck.refusedPosting(edited.errorMessage))
        }
        // The pause after a new file was sent holds for the chat it was sent to (a file sent to
        // Saved Messages says nothing about the channel); a chat not recorded is taken as this one.
        val storedAt = settings.resumeSyncSentAtNow()
        val sentAt = maxOf(lastSendAt, storedAt)
        val sentChat = if (lastSendAt >= storedAt && lastSendAt > 0) lastSendChat else settings.syncSentChatNow()
        if ((sentChat == 0L || sentChat == chatId) && now - sentAt in 0 until RESEND_GUARD_MS) return WriteResult.WaitingForFile
        lastSendAt = now
        lastSendChat = chatId
        settings.setResumeSyncSentAt(now)
        settings.setSyncSentChat(chatId)
        settings.setResumeSyncFile(0L)
        settings.setSyncFileChat(chatId)
        val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
        val sent = waited { td.sendMessage(chatId, null, null, options, null, content) }
        if (sent !is TdlResult.Success) return WriteResult.Failed(Failures.humanise(sent.errorMessage ?: "send"), SyncChannelCheck.refusedPosting(sent.errorMessage))
        // The id given is a stand in until Telegram confirms it: the confirmed one is looked for
        // briefly, then pinned so the viewer sees what it is. Not found yet: found by caption later.
        repeat(CONFIRM_TRIES) {
            delay(CONFIRM_STEP_MS)
            val confirmed = td.getChatHistory(chatId, 0, 0, 10, false).valueOrNull?.messages?.firstOrNull { m ->
                m != null && isFile(m) && m.sendingState == null && m.date * 1000L >= now - 120_000L
            }
            if (confirmed != null) {
                settings.setResumeSyncFile(confirmed.id)
                // A pin in a channel adds a line to it; the file is found by its caption there.
                if (pin) runCatching {
                    OwnedSessions.ensure()
                    td.pinChatMessage(chatId, confirmed.id, true, false)
                }
                return WriteResult.Written
            }
        }
        return WriteResult.Written
    }

    /** Where the file is written before Telegram uploads it: beside TDLib's own folders. */
    internal fun uploadDir(): File? {
        val paths = Td.paths ?: return null
        val parent = paths.databaseDir.parentFile ?: paths.databaseDir
        return File(parent, "tmplayer-sync").takeIf { it.isDirectory || it.mkdirs() }
    }

    /** Room for the file's copy (and what TDLib makes of it) on the disk [dir] is on; unknown room counts as enough. */
    internal fun hasRoomFor(dir: File, bytes: Long): Boolean {
        val free = DiskInfo.of(dir).freeBytes
        return free <= 0L || free >= bytes * 3 + MIN_FREE_BYTES
    }

    /** Uploads of an hour ago and more: Telegram has them by now. */
    internal fun sweep(dir: File) {
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
    }

    // ---- what came without this device's file id ------------------------------------------------

    /** Marks and Watch later lines from another device, given this device's file id from the message. */
    private suspend fun heal(
        watched: WatchedStore,
        repository: ChatRepository,
        budget: Budget,
        chatTitle: suspend (Long) -> String,
    ) {
        val found = HashMap<String, Pair<MediaItem, String>>()
        for ((chatId, messageId) in watched.withoutFile(HEAL_LIMIT)) {
            val key = ProgressKeys.of(chatId, messageId)
            if (key in healTried) continue
            if (!budget.take()) break
            when (val lookup = repository.lookupMedia(chatId, messageId)) {
                is MessageLookup.Found -> found[key] = lookup.item to chatTitle(chatId)
                else -> healTried += key
            }
        }
        watched.healFiles(found)
    }

    private class Budget(private var left: Int) {
        fun take(): Boolean = left-- > 0
    }

    // ---- an older TMPlayer's message ------------------------------------------------------------

    private suspend fun findLegacy(td: TdlClient, chatId: Long, known: Long): Message? {
        if (known > 0L) {
            val message = waited { td.getMessage(chatId, known) }.valueOrNull
            if (message != null && isLegacy(message)) return message
        }
        return waited { td.searchChatMessages(chatId, null, ResumeSyncDoc.TAG, null, 0, 0, SEARCH_LIMIT, null) }
            .valueOrNull?.messages?.firstOrNull { it != null && isLegacy(it) }
    }

    private fun isLegacy(message: Message): Boolean =
        (message.content as? MessageText)?.text?.text?.trimStart()?.startsWith(ResumeSyncDoc.TAG) == true &&
            message.sendingState == null

    /** The older message's places that are newer than this device's, and its removals. */
    private suspend fun takeInLegacy(
        message: Message,
        settings: SettingsStore,
        repository: ChatRepository,
        budget: Budget,
        chatTitle: suspend (Long) -> String,
    ): Int {
        settings.setResumeSyncMessage(message.id)
        val remote = ResumeSyncDoc.decode((message.content as? MessageText)?.text?.text) ?: return 0
        val mine = Backups.resumeOf(settings.rawEntries()).associateBy { ProgressKeys.of(it.chatId, it.messageId) }
        var received = 0
        for (entry in remote.entries.sortedByDescending { it.updatedAt }) {
            val here = mine[entry.key]
            if (here != null && here.updatedAt >= entry.updatedAt) continue
            if (!budget.take()) break
            val found = repository.lookupMedia(entry.chatId, entry.messageId) as? MessageLookup.Found ?: continue
            if (settings.importResume(entry.copy(chatTitle = entry.chatTitle.ifBlank { chatTitle(entry.chatId) }), found.item.fileId)) {
                received++
            }
        }
        for (tomb in remote.tombs) settings.dropSyncedResume(tomb)
        return received
    }

    /** The older message edited to this device's places, when what it says would change. Never sent anew. */
    private suspend fun keepLegacyCurrent(td: TdlClient, chatId: Long, message: Message, settings: SettingsStore, base: SyncBase, now: Long) {
        val text = (message.content as? MessageText)?.text?.text
        val remote = ResumeSyncDoc.decode(text) ?: return
        val records = Backups.resumeOf(settings.rawEntries())
        val local = records.map(ResumeSyncDoc::entryOf)
        val localKeys = local.mapTo(HashSet()) { it.key }
        val tombs = base.resumeKeys.filter { it !in localKeys }.mapNotNull { key ->
            val ids = key.split('_').takeIf { it.size == 2 } ?: return@mapNotNull null
            ResumeSyncDoc.Tomb(ids[0].toLongOrNull() ?: return@mapNotNull null, ids[1].toLongOrNull() ?: return@mapNotNull null, now)
        }
        val merged = ResumeSyncDoc.merge(remote, ResumeSyncDoc.Doc(local, tombs), now)
        val next = ResumeSyncDoc.encode(merged, L.settingsResumeSyncMessage)
        if (next == text) return
        val content = InputMessageText(FormattedText(next, emptyArray()), LinkPreviewOptions(true, "", false, false, false), false)
        waited { td.editMessageText(chatId, message.id, null, content) }
    }

    // ---- flood waits ---------------------------------------------------------------------------

    /**
     * [call], with a FLOOD_WAIT waited out by this call alone, up to [MAX_FLOOD_WAIT_S] and
     * [TRIES] times; any other answer, or a longer wait, is handed back as it came.
     */
    private suspend fun <T> waited(call: suspend () -> TdlResult<T>): TdlResult<T> {
        // Each call of a run: only while its profile is open (HX88), checked before every try.
        OwnedSessions.ensure()
        return waitingOutFlood(TRIES, MAX_FLOOD_WAIT_S, { result -> (result as? TdlResult.Failure)?.let { Failures.floodWaitSeconds(it.message) } }) {
            OwnedSessions.ensure()
            call()
        }
    }

    /** The caption the file is found by; a hashtag, so Telegram's search finds it at once. */
    const val FILE_TAG = "#TMPlayerSyncFile"
    private const val FILE_STEM = "tmplayer-sync"
    private const val TAG = "ResumeSync"
    private const val START_DELAY_MS = SyncScheduler.START_DELAY_MS
    private const val PULL_TIMEOUT_MS = 2_500L
    private const val MIN_PULL_INTERVAL_MS = 60_000L

    /** Automatic syncs, at most this often. */
    const val MIN_INTERVAL_MS = SyncScheduler.MIN_GAP_MS
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L
    private const val SEARCH_LIMIT = 10
    private const val HISTORY_LIMIT = 30
    private const val RESEND_GUARD_MS = 15 * 60 * 1000L
    private const val CONFIRM_TRIES = 10
    private const val CONFIRM_STEP_MS = 2_000L

    /** Messages looked up per sync, for places, the older message's places and file ids together. */
    private const val LOOKUP_BUDGET = 80
    private const val HEAL_LIMIT = 200
    private const val MIN_FREE_BYTES = 1_048_576L
    private const val TRIES = 3
    private const val MAX_FLOOD_WAIT_S = 60
}
