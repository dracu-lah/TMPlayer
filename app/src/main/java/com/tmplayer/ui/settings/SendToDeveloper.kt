package com.tmplayer.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.BuildConfig
import com.tmplayer.data.Failures
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LogBundle
import com.tmplayer.data.LogExport
import com.tmplayer.data.LogUpload
import com.tmplayer.data.LogUploader
import com.tmplayer.data.ReportSender
import com.tmplayer.data.ReportSender.State
import com.tmplayer.data.ReportSender.Why
import com.tmplayer.i18n.Strings
import com.tmplayer.platform.Logger
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.PanelActions
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Send to developer" (reliability plan L5), the upload that sits beside Send logs. A build with no
 * address ([BuildConfig.LOG_UPLOAD_URL] empty: forks, CI) draws nothing: [rememberSendToDeveloper]
 * returns null and [SendToDeveloperButton] is not shown.
 *
 * The press opens a panel that shows what the report holds and its size and asks. Only the press on
 * Send uploads (see [ReportSender] for the steps), and the answer is a code to say in the group, large
 * enough to read across a room. A failure or no connection saves the file and says where.
 */
@Composable
fun rememberSendToDeveloper(): (() -> Unit)? {
    if (!LogUpload.available(BuildConfig.LOG_UPLOAD_URL)) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sender = remember { newSender(context.applicationContext) }
    DisposableEffect(sender) { onDispose { sender.close() } }
    val state by sender.state.collectAsStateWithLifecycle()
    if (state !is State.Idle) {
        SendPanel(
            state = state,
            onSend = { sender.send(scope) },
            onCancelSend = { sender.cancel() },
            onClose = { sender.close() },
            onAgain = {
                sender.close()
                sender.open(scope)
            },
        )
    }
    return { sender.open(scope) }
}

private fun newSender(context: Context) = ReportSender(
    build = { logBundle(context) },
    upload = { text -> LogUploader.send(BuildConfig.LOG_UPLOAD_URL.trim(), text) },
    keep = { text ->
        withContext(Dispatchers.IO) {
            runCatching { SharedDownloads.write(context, LogExport.fileName(), text, "text/plain") }
                .onFailure { Logger.w("Logs", "Report not kept after a failed upload", it) }
                .getOrNull()
        }
    },
)

/** The button beside [SendLogsButton] on an error screen; nothing where the build has no inbox. */
@Composable
fun SendToDeveloperButton(modifier: Modifier = Modifier) {
    val open = rememberSendToDeveloper() ?: return
    val s = LocalStrings.current
    TmSecondaryButton(onClick = open, modifier = modifier) {
        Icon(TmIcons.Send, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.logsSendDev)
    }
}

@Composable
internal fun SendPanel(
    state: State,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onClose: () -> Unit,
    onAgain: () -> Unit,
) {
    val s = LocalStrings.current
    val primary = remember(state::class) { FocusRequester() }
    // Back on a send in flight stops the send and leaves the report up; anywhere else it closes.
    FloatingWindow(onDismiss = { if (state is State.Sending) onCancelSend() else onClose() }, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, 680.dp)
        Column(
            Modifier.width(panel).floatingSurface().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                State.Idle -> Unit
                State.Preparing -> {
                    Title(s.logsSendDevTitle)
                    Body(s.logsSendDevPreparing)
                    PanelActions { TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) } }
                }
                is State.Confirm -> {
                    Title(s.logsSendDevTitle)
                    Body(s.logsSendDevBody(ReportSender.KEEP_DAYS))
                    Text(s.logsSendDevIncludes, style = MaterialTheme.typography.titleSmall, color = Tone.text)
                    for (part in state.report.parts) Body("• " + partLabel(s, part))
                    if (state.report.trimmed) Body(s.logsSendDevTrimmed)
                    Text(s.logsSendDevSize(s.formatter.bytes(state.report.bytes.toLong())), style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                    // Cancel is focused, not Send: an OK meant for something else never sends the report.
                    PanelActions {
                        TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) }
                        TmButton(onClick = onSend) { Text(s.logsSendDevConfirm) }
                    }
                }
                is State.Sending -> {
                    Title(s.logsSendDevTitle)
                    Body(s.logsSendDevSending)
                    PanelActions {
                        TmSecondaryButton(onClick = onCancelSend, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) }
                        TmButton(onClick = {}, enabled = false, loading = true, busyLabel = s.logsSendDevSending) { Text(s.logsSendDevConfirm) }
                    }
                }
                is State.Sent -> {
                    Title(s.logsSendDevSentTitle)
                    // Big and spaced for a TV across the room, and in a monospace face so a 0 and an O are not confused.
                    Text(
                        state.code,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 56.sp,
                            letterSpacing = 4.sp,
                            textAlign = TextAlign.Center,
                        ),
                        color = Tone.accent,
                    )
                    Body(s.logsSendDevSentBody(state.keepDays))
                    PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) } }
                }
                is State.Kept -> {
                    Title(s.logsSendDevKeptTitle)
                    Body(keptMessage(s, state))
                    PanelActions {
                        if (state.why != Why.SLOW_DOWN) TmSecondaryButton(onClick = onAgain) { Text(s.commonTryAgain) }
                        TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) }
                    }
                }
                is State.Failed -> {
                    Title(s.logsSendDevTitle)
                    Body(s.logsSendDevFailed)
                    PanelActions {
                        TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }
                        TmButton(onClick = onAgain, modifier = Modifier.focusRequester(primary)) { Text(s.commonTryAgain) }
                    }
                }
            }
        }
    }
    // The safe choice is focused each time the step changes: Send is the viewer's own press, so it is
    // never focused by an accidental OK from the press that opened the panel (ignoreRelease above).
    LaunchedEffect(state::class) { runCatching { primary.requestFocus() } }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, color = Tone.text)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)

/** What one kind of content is called on the screen that asks. */
internal fun partLabel(s: Strings, part: LogBundle.Part): String = when (part.kind) {
    LogBundle.Kind.CRASH -> s.logsPartCrash
    LogBundle.Kind.DEVICE -> s.logsPartDevice
    LogBundle.Kind.DECODERS -> s.logsPartDecoders
    LogBundle.Kind.SESSIONS -> s.logsPartSessions(part.count)
    LogBundle.Kind.MARKS -> s.logsPartMarks
    LogBundle.Kind.PLAYBACK_TEST -> s.logsPartPlaybackTest
    LogBundle.Kind.APP_LOG -> s.logsPartAppLog(part.count)
    LogBundle.Kind.LOGCAT -> s.logsPartLogcat(part.count)
}

/** The sentence for a report that was kept rather than sent; the place is the file's, or none when saving failed too. */
internal fun keptMessage(s: Strings, kept: State.Kept): String {
    val place = kept.place ?: return s.logsSendDevKeptNowhere
    return when (kept.why) {
        Why.NO_CONNECTION -> s.logsSendDevKeptNoConnection(place)
        Why.SLOW_DOWN -> s.logsSendDevKeptSlow(Failures.waitPhrase(kept.waitSeconds), place)
        Why.REFUSED -> s.logsSendDevKeptRefused(place)
    }
}
