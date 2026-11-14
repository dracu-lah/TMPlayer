package com.tmplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.FileProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.CrashReports
import com.tmplayer.data.Feedback
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LogExport
import com.tmplayer.data.NameReport
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * "Save logs" (CP59), beside "Report a problem": this app's own recent logcat, scrubbed by
 * [LogExport], in a text file the viewer attaches to the report.
 *
 * Saved the way a backup is (see [rememberBackupActions]): through the document picker, so the
 * viewer chooses where, or straight into the shared Download folder on a TV box that has no
 * picker. [say] is the toast.
 */
@Composable
internal fun rememberSaveLogs(say: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = logReport(context)
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    }.onFailure { Logger.w(TAG, "Logs not written", it) }.isSuccess
                }
                say(if (saved) L.logsSaved else L.logsFailed)
            }
        }
    }

    return {
        val name = LogExport.fileName()
        val picked = runCatching { saver.launch(name) }
        if (picked.isFailure) {
            scope.launch {
                val place = withContext(Dispatchers.IO) {
                    runCatching { SharedDownloads.write(context, name, logReport(context), MIME) }
                        .onFailure { Logger.w(TAG, "Logs not written to Download", it) }
                        .getOrNull()
                }
                say(if (place != null) L.logsSavedTo(place) else L.logsFailed)
            }
        }
    }
}

/**
 * "Send logs" on a screen that has gone wrong (an error state, a failed sign in, Telegram out of
 * reach, the crash question), for the player's error screen too: the same scrubbed file as Save
 * logs, handed over in the way each device can.
 *
 * A phone opens the system share sheet with the file attached, so the viewer picks the mail app,
 * Telegram or anything else, and nothing leaves until they do. A TV has no share sheet worth the
 * name and no browser: the file goes into the shared Download folder, and a panel says where and
 * shows a QR code for a new GitHub issue, to write the report from the phone in the other hand.
 *
 * Call it from composition: on a TV the returned action puts that panel up from here.
 */
@Composable
fun rememberSendLogs(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val tv = FormFactor.isTv(context)
    var savedTo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    savedTo?.let { place -> LogsSavedPanel(place, Feedback.issueUrl(feedbackFacts(context)), onClose = { savedTo = null }) }

    return {
        if (!busy) {
            busy = true
            scope.launch {
                val name = LogExport.fileName()
                if (tv) {
                    val place = withContext(Dispatchers.IO) {
                        runCatching { SharedDownloads.write(context, name, logReport(context), MIME) }
                            .onFailure { Logger.w(TAG, "Logs not written to Download", it) }
                            .getOrNull()
                    }
                    if (place != null) savedTo = place else toast(L.logsFailed)
                } else {
                    val file = withContext(Dispatchers.IO) {
                        runCatching { shareableLogFile(context, name) }
                            .onFailure { Logger.w(TAG, "Logs not written for sharing", it) }
                            .getOrNull()
                    }
                    if (file == null || !shareLogFile(context, file)) toast(L.logsFailed)
                }
                busy = false
            }
        }
    }
}

/**
 * The button an error screen offers beside Try again, saying what it does: "Send logs" on a
 * phone, where the share sheet comes next, "Save logs" on a TV, where the file is kept. The
 * quieter of the two buttons, since trying again is still the first thing to do.
 */
@Composable
fun SendLogsButton(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val send = rememberSendLogs()
    TmSecondaryButton(onClick = send, modifier = modifier) {
        Icon(TmIcons.Logs, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (FormFactor.isTv(LocalContext.current)) s.logsSave else s.logsSend)
    }
}

/**
 * The report written into `cache/logs/`, the one folder of this app's that the FileProvider hands
 * out for logs (see `update_paths.xml`). Older ones go first: only the file being shared is kept.
 */
private suspend fun shareableLogFile(context: Context, name: String): File {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, name).apply { writeText(logReport(context)) }
}

/** The share sheet, with the file and a subject line for a mail app. False if nothing could take it. */
private fun shareLogFile(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        // For the maintainer, who reads reports in English, like the file's own header.
        .putExtra(Intent.EXTRA_SUBJECT, "TMPlayer logs") // i18n-ok: mail subject for the maintainer
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, L.logsSendTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}.onFailure { Logger.w(TAG, "Share sheet not opened", it) }.isSuccess

/**
 * A TV's answer to Send logs: where the file went, and a code for a new GitHub issue to report it
 * from the phone. Close holds the remote, so an accidental OK only closes it.
 */
@Composable
private fun LogsSavedPanel(place: String, issueUrl: String, onClose: () -> Unit) {
    val s = LocalStrings.current
    val close = remember { FocusRequester() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = issueUrl) {
        value = withContext(Dispatchers.Default) { QrCode.render(issueUrl, QR_PIXELS) }
    }
    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, 680.dp)
        Row(
            Modifier.width(panel).floatingSurface().padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(Corner.ExtraLarge)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    // A scanner needs a quiet margin of the code's own white to find it.
                    Image(it, contentDescription = s.supportQrFor(s.logsReportGithub), modifier = Modifier.fillMaxSize().padding(12.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.logsSaved, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                Text(s.logsTvSavedBody(place), style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Text(s.commonClose) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/**
 * "Save name check", a hidden developer option: the last chat listing's file names and how they
 * were read ([NameReport]), written to `name-check/` in this app's own external files folder
 * (`Android/data/<package>/files`), where a file manager or `adb pull` finds it. Nothing is sent.
 */
@Composable
internal fun rememberSaveNameCheck(say: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        val items = NameReport.lastListing
        if (items.isEmpty()) {
            say(L.logsNameReportEmpty)
        } else {
            scope.launch {
                val place = withContext(Dispatchers.Default) {
                    runCatching {
                        val folder = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "name-check").apply { mkdirs() }
                        val file = java.io.File(folder, NameReport.fileName(System.currentTimeMillis()))
                        file.writeText(NameReport.text(items))
                        file.absolutePath
                    }.onFailure { Logger.w(TAG, "Name check not written", it) }.getOrNull()
                }
                say(if (place != null) L.logsNameReportSaved(place) else L.logsFailed)
            }
        }
    }
}

/**
 * The file's text: the facts a bug report carries (version, model, Android version, TV or
 * phone), then this process's last [LogExport.MAX_LINES] logcat lines with the names this device
 * has seen, ids, phone numbers, usernames and file names taken out.
 *
 * An app may read its own log lines without any permission; `--pid` keeps it to this process,
 * which is all it could read anyway, and the player runs in the same one.
 */
private suspend fun logReport(context: Context): String {
    val lines = runCatching { ownLogcat() }
        .onFailure { Logger.w(TAG, "logcat could not be read", it) }
        .getOrDefault(emptyList())
    val known = LogExport.knownNames(SettingsStore(context), WatchedStore(context))
    return LogExport.report(feedbackFacts(context), lines, known, extra = runCatching { CrashReports.logLines() }.getOrDefault(emptyList()))
}

private fun ownLogcat(): List<String> {
    val process = ProcessBuilder(
        "logcat", "-d", "-v", "threadtime", "-t", LogExport.MAX_LINES.toString(), "--pid=${Process.myPid()}",
    ).redirectErrorStream(true).start()
    return try {
        process.inputStream.bufferedReader().useLines { it.toList() }.takeLast(LogExport.MAX_LINES)
    } finally {
        // The timed wait is Android 8's. Earlier the output has already been read to its end,
        // which logcat -d reaches only as it exits, so destroying it straight away loses nothing.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) process.waitFor(2, TimeUnit.SECONDS)
        process.destroy()
    }
}

private const val MIME = "text/plain"
private const val TAG = "Logs"
private const val QR_PIXELS = 560
