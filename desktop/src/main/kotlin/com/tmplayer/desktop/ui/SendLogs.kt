package com.tmplayer.desktop.ui

import com.tmplayer.ui.components.ToastKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Feedback
import com.tmplayer.data.LogBundle
import com.tmplayer.data.LogExport
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.os.DesktopLog
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.os.UserDirs
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The desktop's "Send logs", for a screen that has gone wrong (an error state, a failed sign in,
 * Telegram out of reach) and for the player's error screen too: Save logs writes the scrubbed file
 * into the Downloads folder and shows it in the file manager, and Report on GitHub opens a new
 * issue with this machine's facts filled in, to attach it to. Nothing is sent by the app itself.
 */
object SendLogs {

    /**
     * Writes the report ([LogExport]) into [folder] and returns the file, or null when there is
     * nothing to write or it could not be written.
     */
    suspend fun save(folder: File = UserDirs.downloads()): File? = withContext(Dispatchers.IO) {
        runCatching {
            val lines = DesktopLog.recentLines(LogExport.MAX_LINES)
            if (lines.isEmpty()) return@runCatching null
            val known = LogExport.knownNames(DesktopServices.settings, DesktopServices.watched)
            folder.mkdirs()
            File(folder, LogExport.fileName()).apply { writeText(bundle(lines, known).text) }
        }.onFailure { Logger.w(TAG, "Logs not saved", it) }.getOrNull()
    }

    /** The bundle's text for feedback (HX64), or null when there is no log yet. Off the main thread. */
    suspend fun bundleText(): String? = withContext(Dispatchers.IO) {
        val lines = DesktopLog.recentLines(LogExport.MAX_LINES)
        if (lines.isEmpty()) return@withContext null
        bundle(lines, LogExport.knownNames(DesktopServices.settings, DesktopServices.watched)).text
    }

    /**
     * The same bundle as the phone's (L5), from the desktop's own log file: the facts of this machine
     * and the log's last lines, scrubbed and under the cap. The desktop keeps no playback trace.
     */
    fun bundle(lines: List<String>, known: Collection<String>): LogBundle.Result = LogBundle.build(
        desktopFeedbackFacts(),
        LogBundle.Parts(device = machineLines(), appLog = lines),
        known,
    )

    private fun machineLines(): List<String> = buildList {
        val rt = Runtime.getRuntime()
        add("Operating system: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        add("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        add("CPU cores: ${rt.availableProcessors()}, memory limit of the app ${rt.maxMemory() / (1024 * 1024)} MB")
    }

    /** A new issue on GitHub, with the version, the system and the language filled in. */
    fun issueUrl(): String = Feedback.issueUrl(desktopFeedbackFacts())

    private const val TAG = "SendLogs"
}

/**
 * The two buttons under an error: Save logs, which says where the file went and shows it in its
 * folder, and Report on GitHub. Wrapped, so a narrow window or a long language never squeezes them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendLogsActions(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var busy by remember { mutableStateOf(false) }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val file = SendLogs.save()
                    busy = false
                    if (file == null) {
                        toast(L.logsFailed, ToastKind.Error)
                    } else {
                        toast(L.logsSavedTo(file.absolutePath), ToastKind.Success)
                        OpenExternal.reveal(file)
                    }
                }
            },
        ) {
            Icon(TmIcons.Logs, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.logsSave)
        }
        TextButton(onClick = { OpenExternal.browse(SendLogs.issueUrl()) }) {
            Icon(TmIcons.Bug, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.logsReportGithub)
        }
    }
}
