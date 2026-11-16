package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageSendOptions
import kotlinx.coroutines.CancellationException
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

    override suspend fun send(text: String, fileName: String): BackupSend {
        val session = Td.awaitConnectedSessionOrNull() ?: return BackupSend.Failed(L.signoutBackupOffline)
        return try {
            val td = session.client
            val myId = Td.myId().takeIf { it != 0L } ?: return BackupSend.Failed(L.signoutBackupOffline)
            val chat = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
            val dir = ResumeSync.uploadDir() ?: return BackupSend.Failed(L.signoutBackupNoFolder)
            ResumeSync.sweep(dir)
            val now = System.currentTimeMillis()
            val local = File(File(dir, "backup-$now").apply { mkdirs() }, fileName)
            local.writeText(text)
            val caption = FormattedText("$TAG\n${L.signoutBackupCaption}", emptyArray())
            val content = InputMessageDocument(InputDocument(InputFileLocal(local.absolutePath), null, true), caption)
            val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
            val sent = waitingOutFlood(3, 60, { r: TdlResult<*> -> (r as? TdlResult.Failure)?.let { Failures.floodWaitSeconds(it.message) } }) {
                td.sendMessage(chat, null, null, options, null, content)
            }
            if (sent !is TdlResult.Success) {
                return BackupSend.Failed(Failures.humanise(sent.errorMessage ?: "send"))
            }
            // Telegram has it once the message loses its "sending" state; the viewer is left on
            // the dialog until then, not signed out over a half sent file.
            repeat(CONFIRM_TRIES) {
                delay(CONFIRM_STEP_MS)
                val confirmed = td.getChatHistory(chat, 0, 0, 10, false).valueOrNull?.messages?.any { m ->
                    m != null && m.sendingState == null && m.date * 1000L >= now - 120_000L &&
                        (m.content as? MessageDocument)?.caption?.text?.trimStart()?.startsWith(TAG) == true
                } == true
                if (confirmed) {
                    local.parentFile?.deleteRecursively()
                    return BackupSend.Sent
                }
            }
            BackupSend.Failed(L.signoutBackupSlow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("SavedMessagesBackup", "Sending the backup failed", e)
            BackupSend.Failed(Failures.humanise(e))
        }
    }

    private const val CONFIRM_TRIES = 15
    private const val CONFIRM_STEP_MS = 2_000L
}

/**
 * The backup before signing out: builds the file, sends it, and never throws and never takes
 * longer than [timeoutMs], so a dead line cannot keep the viewer from signing out. What comes
 * back is for the dialog to show, with the way out next to it (Sign out anyway).
 */
object SignOutBackup {

    /** A minute: a backup is small, and the viewer is waiting on a dialog. */
    const val TIMEOUT_MS = 60_000L

    suspend fun save(
        export: suspend () -> String,
        fileName: String,
        sender: BackupSender = SavedMessagesBackup,
        timeoutMs: Long = TIMEOUT_MS,
    ): BackupSend {
        val text = try {
            export()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupSend.Failed(L.backupFailed)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { sender.send(text, fileName) } ?: BackupSend.Failed(L.signoutBackupSlow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupSend.Failed(Failures.humanise(e))
        }
    }
}
