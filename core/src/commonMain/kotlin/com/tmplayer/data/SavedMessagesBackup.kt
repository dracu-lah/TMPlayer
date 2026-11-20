package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.MessageSendOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What sending one backup to the viewer's own Saved Messages came to. */
sealed interface BackupSend {
    data object Sent : BackupSend

    /** [reason] is plain words for the viewer, never a stack trace. */
    data class Failed(val reason: String) : BackupSend
}

/** Where a backup goes; Telegram's Saved Messages in the app, a fake in tests. */
fun interface BackupSender {
    suspend fun send(text: String, fileName: String): BackupSend
}

/**
 * One settings backup ([Backups]) put into the viewer's own Saved Messages as a file, for the
 * moment before signing out ("Save a backup before you sign out"): the account being left is
 * the only one it is sent for, to its own chat with itself, one file, once, by the viewer's
 * own press. It is not the sync file ([ResumeSync]): that one is replaced in place and pinned;
 * this one is a dated copy the viewer can keep or delete, and a restore reads it back (Settings,
 * About and help, Restore).
 *
 * The file is waited for until Telegram has it, because signing out destroys the upload that
 * was still under way.
 */
object SavedMessagesBackup : BackupSender {

    /** The caption the file is told apart by, a hashtag so Telegram's search finds it. */
    const val TAG = "#TMPlayerBackup"

    override suspend fun send(text: String, fileName: String): BackupSend = send(text, fileName, null)

    /**
     * [settings] given, the file goes to the private channel the viewer chose for it (HX87) when
     * only they are in it, else to Saved Messages; a channel that cannot be checked right now is
     * not a reason to keep the viewer from signing out, so Saved Messages takes it then.
     */
    suspend fun send(text: String, fileName: String, settings: SettingsStore?): BackupSend {
        // The profile signing out's own client (HX88), never another's that came up meanwhile.
        val owner = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        val session = Td.sessionFor(owner) ?: return BackupSend.Failed(L.signoutBackupOffline)
        var folder: File? = null
        return try {
            val td = session.client
            val myId = Td.myId().takeIf { it != 0L } ?: return BackupSend.Failed(L.signoutBackupOffline)
            val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
            val chat = SyncDestinations.chatForBackup(settings, myId, saved, TdChannelProbe(td))
            val dir = ResumeSync.uploadDir() ?: return BackupSend.Failed(L.signoutBackupNoFolder)
            ResumeSync.sweep(dir)
            if (!ResumeSync.hasRoomFor(dir, text.toByteArray().size.toLong())) return BackupSend.Failed(L.syncchannelNoSpace)
            folder = File(dir, "$FOLDER_PREFIX${System.currentTimeMillis()}").apply { mkdirs() }
            val local = File(folder, fileName)
            local.writeText(text)
            val caption = FormattedText("$TAG\n${L.signoutBackupCaption}", emptyArray())
            val content = InputMessageDocument(InputDocument(InputFileLocal(local.absolutePath), null, true), caption)
            val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
            // The channel is checked once more right before the post (the reads above took time and
            // somebody may have joined); not safe or not known: Saved Messages takes it.
            val target = if (chat == saved || settings == null) chat else SyncDestinations.backupChatBeforeWrite(settings, TdChannelProbe(td), chat, saved)
            // Telegram refusing the post in the channel: Saved Messages, once, and nowhere else.
            postWithFallback(target, saved, settings) { to -> post(td, to, options, content) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("SavedMessagesBackup", "Sending the backup failed", e)
            BackupSend.Failed(Failures.humanise(e))
        } finally {
            // Sent or not, the copy of the viewer's lists made for the upload does not stay on disk.
            folder?.deleteRecursively()
        }
    }

    /** One post of the file to [chat]: Telegram's own word that it arrived, [PostOutcome.raw] the error text when it did not. */
    private suspend fun post(td: TdlClient, chat: Long, options: MessageSendOptions, content: InputMessageDocument): PostOutcome =
        coroutineScope {
            // By the temporary id the send gave: no clock is compared, so a device whose clock is wrong
            // confirms like any other. Subscribed before the send, so an answer that beats the send's
            // own reply is not missed.
            val results = java.util.concurrent.ConcurrentHashMap<Long, PostOutcome>()
            val ok = launch(start = CoroutineStart.UNDISPATCHED) {
                td.messageSendSucceededUpdates.collect { results[it.oldMessageId] = PostOutcome(BackupSend.Sent) }
            }
            val failed = launch(start = CoroutineStart.UNDISPATCHED) {
                td.messageSendFailedUpdates.collect {
                    results[it.oldMessageId] = PostOutcome(BackupSend.Failed(Failures.humanise(it.error.message)), it.error.message)
                }
            }
            try {
                val sent = waitingOutFlood(3, 60, { r: TdlResult<*> -> (r as? TdlResult.Failure)?.let { Failures.floodWaitSeconds(it.message) } }) {
                    OwnedSessions.ensure()
                    td.sendMessage(chat, null, null, options, null, content)
                }
                if (sent !is TdlResult.Success) return@coroutineScope PostOutcome(BackupSend.Failed(Failures.humanise(sent.errorMessage ?: "send")), sent.errorMessage)
                val id = sent.result.id
                // Already past "sending" in the reply itself: nothing to wait for.
                if (sent.result.sendingState == null) return@coroutineScope PostOutcome(BackupSend.Sent)
                repeat(CONFIRM_TRIES) {
                    results[id]?.let { return@coroutineScope it }
                    delay(CONFIRM_STEP_MS)
                }
                results[id] ?: PostOutcome(BackupSend.Failed(L.signoutBackupSlow))
            } finally {
                ok.cancel()
                failed.cancel()
            }
        }

    /** What one post came to; [raw] is Telegram's error text (for [SyncChannelCheck.refusedPosting]), null when none. */
    internal class PostOutcome(val result: BackupSend, val raw: String? = null)

    /**
     * Posts to [chat]; when that is not Saved Messages and Telegram refuses the account a post there
     * (HX87), the refusal is noted (Saved Messages for the next hour) and the file goes ONCE to
     * [saved], the only other chat allowed. Never to anywhere else, never in a loop: the second
     * answer is final.
     */
    internal suspend fun postWithFallback(
        chat: Long,
        saved: Long,
        settings: SettingsStore?,
        post: suspend (Long) -> PostOutcome,
    ): BackupSend {
        val first = post(chat)
        if (chat == saved || first.result is BackupSend.Sent || !SyncChannelCheck.refusedPosting(first.raw)) return first.result
        if (settings != null) SyncDestinations.notePostingRefused(settings)
        return post(saved).result
    }

    /** Leftovers of an upload that never finished: gone with the account (sign out). */
    fun forgetFiles() {
        val dir = ResumeSync.uploadDir() ?: return
        dir.listFiles()?.filter { it.name.startsWith(FOLDER_PREFIX) }?.forEach { it.deleteRecursively() }
    }

    private const val FOLDER_PREFIX = "backup-"
    private const val CONFIRM_TRIES = 15
    private const val CONFIRM_STEP_MS = 2_000L
}

/**
 * The backup before signing out: builds the file, sends it, and never throws and never takes
 * longer than [timeoutMs], so a dead line cannot keep the viewer from signing out. What comes
 * back is for the dialog to show, with the way out next to it (Sign out anyway).
 */
object SignOutBackup {

    /** Forty seconds: a backup is small, and the viewer is waiting on a dialog. */
    const val TIMEOUT_MS = 40_000L

    /** Where the file goes: Telegram's Saved Messages, replaced only by a test's fake. */
    @Volatile
    var sender: BackupSender = SavedMessagesBackup

    suspend fun save(
        export: suspend () -> String,
        fileName: String,
        sender: BackupSender = this.sender,
        timeoutMs: Long = TIMEOUT_MS,
        /** The viewer's settings: where the file goes when they chose a channel (HX87). */
        settings: SettingsStore? = null,
    ): BackupSend {
        val text = try {
            export()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupSend.Failed(L.backupFailed)
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                if (sender === SavedMessagesBackup) SavedMessagesBackup.send(text, fileName, settings) else sender.send(text, fileName)
            } ?: BackupSend.Failed(L.signoutBackupSlow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupSend.Failed(Failures.humanise(e))
        }
    }
}
