package com.tmplayer.ui.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.BuildConfig
import com.tmplayer.data.Backup
import com.tmplayer.data.BackupRead
import com.tmplayer.data.BackupService
import com.tmplayer.data.BackupWords
import com.tmplayer.data.RestorePlan
import com.tmplayer.data.RestoreRun
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The Back up and Restore rows' work, for Settings on a phone and on a TV. */
internal class BackupActions(
    val backUp: () -> Unit,
    val restore: () -> Unit,
    /** Merges a backup in, once the viewer has confirmed the [RestorePlan] they were shown. */
    val apply: (Backup) -> Unit,
)

/**
 * Back up and restore through the system's document picker, where the device has one: a phone
 * always does, and the viewer chooses the folder and the cloud it goes to.
 *
 * Some TV boxes ship without the picker, and launching it there throws. Those get the plain way
 * round instead: the file is written to the shared Download folder, the toast says where, and
 * Restore reads the newest backup found there. A file is never read whole if it is larger than any
 * backup could be, so picking a film by mistake cannot exhaust the memory.
 *
 * [onPlan] receives what a picked file would change, for the confirm prompt; [say] is the toast.
 */
@Composable
internal fun rememberBackupActions(
    onPlan: (RestorePlan) -> Unit,
    say: (String) -> Unit,
): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember {
        BackupService(
            settings = SettingsStore(context),
            watched = WatchedStore(context),
            metadata = OnlineMetadata.current?.store,
            appVersion = BuildConfig.VERSION_NAME,
            bookmarks = com.tmplayer.data.BookmarkStore(context),
        )
    }

    suspend fun offer(text: String?) {
        if (text == null) {
            say(L.backupReadFailed)
            return
        }
        when (val read = service.read(text)) {
            is BackupRead.Ok -> {
                val plan = service.plan(read.backup)
                if (plan.nothing) say(L.backupRestoreNothing) else onPlan(plan)
            }
            else -> BackupWords.refusal(read)?.let(say)
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = service.export()
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    }.onFailure { Logger.w(TAG, "Backup not written", it) }.isSuccess
                }
                say(if (saved) L.backupSaved else L.backupFailed)
            }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use(::readSmall) }.getOrNull()
                }
                offer(text)
            }
        }
    }

    // Built afresh on every composition, which costs nothing, so [say] and [onPlan] are never stale.
    return BackupActions(
        backUp = {
            val picked = runCatching { saver.launch(service.fileName()) }
            if (picked.isFailure) {
                scope.launch {
                    val place = withContext(Dispatchers.IO) {
                        runCatching { SharedDownloads.write(context, service.fileName(), service.export()) }
                            .onFailure { Logger.w(TAG, "Backup not written to Download", it) }
                            .getOrNull()
                    }
                    say(if (place != null) L.backupSavedTo(place) else L.backupFailed)
                }
            }
        },
        restore = {
            // Several types, because providers disagree on what a .json file is.
            val picked = runCatching { opener.launch(arrayOf(MIME, "text/*", "application/octet-stream")) }
            if (picked.isFailure) {
                scope.launch {
                    val newest = withContext(Dispatchers.IO) { runCatching { SharedDownloads.newest(context) }.getOrNull() }
                    if (newest == null) say(L.backupNoneFound(SharedDownloads.place(context))) else offer(newest)
                }
            }
        },
        apply = { backup ->
            // In the open profile's scope, so a rotation or leaving Settings does not cut a restore short
            // but a switch does (SEC2-04: nothing of it lands in the next profile), and once at a time
            // (RestoreRun): the confirm pressed twice merges the backup once.
            RestoreRun.start(
                work = { withContext(Dispatchers.IO) { service.restore(backup) } },
            ) { outcome ->
                when (outcome) {
                    RestoreRun.Outcome.Done -> say(L.backupRestored)
                    RestoreRun.Outcome.Failed -> say(L.backupRestoreFailed)
                    RestoreRun.Outcome.AlreadyRunning -> Unit
                }
            }
        },
    )
}

/**
 * The shared Download folder, for a device with no document picker. Through MediaStore from
 * Android 10, which needs no permission; before that, the app's own folder under Android/data,
 * which a file manager or a USB cable can still reach. "Save logs" (CP59) writes there too.
 */
internal object SharedDownloads {

    fun place(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.DIRECTORY_DOWNLOADS
        } else {
            legacyDir(context)?.absolutePath ?: Environment.DIRECTORY_DOWNLOADS
        }

    /** Writes [text] as [name] and returns where it went, in words for the toast. */
    fun write(context: Context, name: String, text: String, mime: String = MIME): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            // MediaStore may have renamed it to avoid a clash ("... (1).json"); say the real name.
            val written = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
            return "${Environment.DIRECTORY_DOWNLOADS}/$written"
        }
        val dir = legacyDir(context)!!.apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text)
        return file.absolutePath
    }

    /** The newest backup this app can see in that folder, as text, or null when there is none. */
    fun newest(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val id = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("tmplayer-backup-%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
            val uri = android.content.ContentUris.withAppendedId(collection, id)
            return resolver.openInputStream(uri)?.use(::readSmall)
        }
        val newest = legacyDir(context)?.listFiles { f -> f.name.startsWith("tmplayer-backup-") }
            ?.maxByOrNull { it.lastModified() } ?: return null
        return newest.inputStream().use(::readSmall)
    }

    private fun legacyDir(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
}

/** The text of a stream, or an error past [MAX_BYTES]: no backup comes near it. */
private fun readSmall(input: InputStream): String {
    // By hand: InputStream.readNBytes only arrived in Android 13.
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        require(out.size() <= MAX_BYTES) { "Too large for a backup" }
    }
    return out.toString(Charsets.UTF_8.name())
}

private const val MIME = "application/json"
private const val MAX_BYTES = 32 * 1024 * 1024
private const val TAG = "Backup"
