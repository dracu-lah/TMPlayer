package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Feedback
import com.tmplayer.data.FeedbackDraft
import com.tmplayer.data.FeedbackReport
import com.tmplayer.data.FeedbackReports
import com.tmplayer.data.FeedbackSender.State
import com.tmplayer.data.FeedbackUpload
import com.tmplayer.data.MediaText
import com.tmplayer.i18n.Strings
import com.tmplayer.platform.Background
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * "Report a problem" and "Request a feature" written in the app (HX64), on every platform: the
 * form, the confirm step that shows word for word what is sent, and what happened after. The phone
 * and the desktop draw [FeedbackDialog]; the television draws [FeedbackBody] in its own panel
 * beside a QR code for the phone route, so the words and the order are the same everywhere.
 */
class FeedbackActions(
    val onEdit: ((FeedbackDraft) -> FeedbackDraft) -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onClose: () -> Unit,
    /** The GitHub route, kept beside the inbox; null where the platform shows it its own way (the TV's QR code). */
    val onGithub: (() -> Unit)? = null,
    val onEmail: (() -> Unit)? = null,
)

/** Test tags, for the focus and screenshot tests. */
object FeedbackTags {
    const val MESSAGE = "feedback-message"
    const val CONTACT = "feedback-contact"
    const val LOGS = "feedback-logs"
    const val CODE = "feedback-code"
    fun kind(kind: FeedbackReport.Kind) = "feedback-kind-${kind.wire}"
}

/**
 * The one place the app draws the feedback window: it opens the process's sender ([FeedbackReports])
 * for [kind] with this device's [facts] and follows it. [onDone] runs once it is closed. [tvPanel]
 * draws it for a remote; left out, the dialog is used. Nothing is drawn without an inbox: the
 * caller shows the GitHub and e-mail routes instead ([FeedbackReports.available]).
 */
@Composable
fun FeedbackHost(
    kind: FeedbackReport.Kind,
    facts: Feedback.Facts,
    onDone: () -> Unit,
    onGithub: (() -> Unit)? = null,
    onEmail: (() -> Unit)? = null,
    tvPanel: (@Composable (State, Boolean, FeedbackActions) -> Unit)? = null,
) {
    val sender = FeedbackReports.sender ?: return
    val state by sender.state.collectAsState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(sender) {
        sender.open(Background.scope, kind, facts)
        opened = true
    }
    // The screen under it went away (a page closed, the window shut): what is typed is kept.
    DisposableEffect(sender) { onDispose { if (sender.state.value !is State.Idle) sender.close(Background.scope) } }
    // Closed (Close, Back, a tap beside it): the caller's flag goes too.
    LaunchedEffect(opened, state) { if (opened && state is State.Idle) onDone() }
    val actions = remember(sender, onGithub, onEmail) {
        FeedbackActions(
            onEdit = sender::edit,
            onNext = { sender.review(Background.scope) },
            onBack = sender::back,
            onSend = { sender.send(Background.scope) },
            onClose = { sender.close(Background.scope) },
            onGithub = onGithub,
            onEmail = onEmail,
        )
    }
    if (state is State.Idle || state is State.Opening) return
    if (tvPanel != null && !isTouch()) {
        tvPanel(state, sender.logsAvailable, actions)
    } else {
        FeedbackDialog(state, sender.logsAvailable, actions)
    }
}

/** The phone's and the desktop's window: [FeedbackBody] in the app's dialog, with each step's buttons. */
@Composable
fun FeedbackDialog(state: State, logsAvailable: Boolean, actions: FeedbackActions) {
    val s = LocalStrings.current
    val safe = remember { FocusRequester() }
    TmAlertDialog(
        // A tap beside it, or Back, closes: what is typed stays on this device.
        onDismissRequest = actions.onClose,
        title = { Text(feedbackHeading(s, state), maxLines = 2) },
        text = {
            Column(
                Modifier.heightIn(max = dialogBodyMax(BODY_MAX)).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FeedbackBody(state, logsAvailable, actions)
                if (state is State.Editing || state is State.Refused) OtherWays(actions)
            }
        },
        confirmButton = {
            when (state) {
                is State.Editing -> TextButton(onClick = actions.onNext) { Text(s.feedbackNext) }
                is State.Review -> TextButton(onClick = actions.onSend) { Text(s.feedbackSend) }
                is State.Queued -> TextButton(onClick = actions.onSend) { Text(s.commonTryAgain) }
                is State.Refused -> TextButton(onClick = actions.onBack) { Text(s.feedbackEdit) }
                is State.Sent -> TextButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonClose) }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is State.Editing, is State.Refused -> TextButton(onClick = actions.onClose) { Text(s.commonClose) }
                is State.Review, is State.PreparingLogs ->
                    TextButton(onClick = actions.onBack, modifier = Modifier.focusRequester(safe)) { Text(s.commonBack) }
                is State.Sending -> TextButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonCancel) }
                is State.Queued -> Row {
                    TextButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonClose) }
                    TextButton(onClick = actions.onBack) { Text(s.feedbackEdit) }
                }
                else -> Unit
            }
        },
    )
    // The press that changes nothing is under the keyboard on every step but the form, where the
    // message field takes it: a stray Enter never sends.
    LaunchedEffect(state::class) {
        if (state is State.Editing) return@LaunchedEffect
        // A frame first: the step's buttons are placed in it, after the form's field has let go.
        withFrameNanos { }
        runCatching { safe.requestFocus() }
    }
}

/** GitHub and e-mail, kept as the ways round (a viewer who wants a public issue, or the inbox refusing). */
@Composable
private fun OtherWays(actions: FeedbackActions) {
    val s = LocalStrings.current
    if (actions.onGithub == null && actions.onEmail == null) return
    Text(s.feedbackOtherWays, style = MaterialTheme.typography.labelMedium, color = Tone.muted, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.onGithub?.let { TextButton(onClick = it) { Text(s.feedbackOpenGithub) } }
        actions.onEmail?.let { TextButton(onClick = it) { Text(s.feedbackEmail) } }
    }
}

/** The heading of each step. */
fun feedbackHeading(s: Strings, state: State): String = when (state) {
    is State.Editing -> if (state.draft.kind == FeedbackReport.Kind.Feature) s.feedbackFeatureTitle else s.feedbackTitle
    is State.PreparingLogs, is State.Review, is State.Sending -> s.feedbackReviewTitle
    is State.Sent -> s.feedbackSentTitle
    is State.Queued -> s.feedbackQueuedTitle
    is State.Refused -> s.feedbackRefusedTitle
    State.Idle, State.Opening -> s.feedbackTitle
}

/**
 * The words and the fields of each step, the same on every platform. The form's message field
 * takes the keyboard (or the remote) as it opens; on a television the arrows leave each field.
 */
@Composable
fun FeedbackBody(state: State, logsAvailable: Boolean, actions: FeedbackActions) {
    val s = LocalStrings.current
    when (state) {
        is State.Editing -> FeedbackFields(state, logsAvailable, actions)
        is State.PreparingLogs -> Busy(s.feedbackLogsPreparing)
        is State.Review -> {
            val report = state.report
            Text(s.feedbackReviewBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            TitleReportLine(s.feedbackKindLabel, if (report.kind == FeedbackReport.Kind.Feature) s.feedbackKindFeature else s.feedbackKindProblem)
            TitleReportLine(s.feedbackMessageLabel, report.message)
            TitleReportLine(s.feedbackContactLabel, report.contact ?: s.feedbackContactNone)
            TitleReportLine(s.feedbackAboutDevice, aboutLine(report.facts))
            TitleReportLine(s.feedbackLogsLabel, logsLine(s, state))
        }
        is State.Sending -> Busy(s.feedbackSending)
        is State.Sent -> {
            // Large, in a monospace face, so it can be read out from across the room; FB-7K3Q-M4XP fits a phone.
            Text(
                state.code,
                modifier = Modifier.fillMaxWidth().testTag(FeedbackTags.CODE),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                ),
                color = Tone.accent,
            )
            Text(s.feedbackSentBody(FeedbackUpload.KEEP_DAYS), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            if (state.logsMissed) Text(s.feedbackSentLogsMissed, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
        is State.Queued -> Text(if (state.waitSeconds > 0) s.feedbackQueuedSlow else s.feedbackQueuedBody, style = MaterialTheme.typography.bodyMedium)
        is State.Refused -> Text(s.feedbackRefusedBody(state.status), style = MaterialTheme.typography.bodyMedium)
        State.Idle, State.Opening -> Unit
    }
}

@Composable
private fun FeedbackFields(state: State.Editing, logsAvailable: Boolean, actions: FeedbackActions) {
    val s = LocalStrings.current
    val draft = state.draft
    val touch = isTouch()
    val message = remember { FocusRequester() }
    Text(s.feedbackFormBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    TabSwitch(
        choices = FeedbackReport.Kind.entries,
        selected = draft.kind,
        title = { if (it == FeedbackReport.Kind.Problem) s.feedbackKindProblem else s.feedbackKindFeature },
        tag = { FeedbackTags.kind(it) },
        onPick = { kind -> actions.onEdit { it.copy(kind = kind) } },
    )
    val problem = state.problem
    val count = FeedbackReport.cleanMessage(draft.message).length
    OutlinedTextField(
        value = draft.message,
        onValueChange = { text -> actions.onEdit { it.copy(message = text.take(FeedbackReport.MAX_MESSAGE * 2)) } },
        modifier = Modifier.fillMaxWidth().focusRequester(message).dpadLeavesFieldOnTv().testTag(FeedbackTags.MESSAGE),
        label = { Text(if (draft.kind == FeedbackReport.Kind.Feature) s.feedbackMessageFeature else s.feedbackMessageProblem) },
        minLines = 4,
        maxLines = 10,
        isError = problem == FeedbackReport.Problem.Empty || problem == FeedbackReport.Problem.TooLong || count > FeedbackReport.MAX_MESSAGE,
        supportingText = {
            Text(
                when {
                    problem == FeedbackReport.Problem.Empty -> s.feedbackMessageEmpty
                    count > FeedbackReport.MAX_MESSAGE -> s.feedbackMessageTooLong(count, FeedbackReport.MAX_MESSAGE)
                    else -> s.feedbackMessageCount(count, FeedbackReport.MAX_MESSAGE)
                },
            )
        },
        // On a television the field takes the remote, not the screen: the keyboard comes up on OK.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, showKeyboardOnFocus = touch),
    )
    val contactLong = FeedbackReport.cleanLine(draft.contact).length > FeedbackReport.MAX_CONTACT
    OutlinedTextField(
        value = draft.contact,
        onValueChange = { text -> actions.onEdit { it.copy(contact = text.take(FeedbackReport.MAX_CONTACT * 2)) } },
        modifier = Modifier.fillMaxWidth().dpadLeavesFieldOnTv().testTag(FeedbackTags.CONTACT),
        label = { Text(s.feedbackContactLabel) },
        singleLine = true,
        isError = contactLong,
        supportingText = { Text(if (contactLong) s.feedbackContactTooLong(FeedbackReport.MAX_CONTACT) else s.feedbackContactHint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, showKeyboardOnFocus = touch),
    )
    if (logsAvailable) {
        Column(Modifier.testTag(FeedbackTags.LOGS)) {
            TitleTick(s.feedbackLogsTick, draft.includeLogs) { on -> actions.onEdit { it.copy(includeLogs = on) } }
            Text(s.feedbackLogsDetail, style = MaterialTheme.typography.bodySmall, color = Tone.muted, modifier = Modifier.padding(start = 32.dp))
        }
    }
    // The message field takes the keyboard or the remote as the form opens.
    LaunchedEffect(Unit) { runCatching { message.requestFocus() } }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 3.dp, color = Tone.accent)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Android TV or Google TV, Mi Box S, Android 9, version 1.40.0, app language en": the facts as sent. */
fun aboutLine(facts: Feedback.Facts): String = listOf(
    facts.platform.option,
    facts.device,
    facts.version.takeIf { it.isNotBlank() }?.let { "TMPlayer $it" }.orEmpty(),
    facts.language,
).filter { it.isNotBlank() }.joinToString(", ")

private fun logsLine(s: Strings, state: State.Review): String {
    val sent = state.draft.logsCode
    val logs = state.logs
    return when {
        !state.draft.includeLogs -> s.feedbackLogsNone
        sent != null -> sent
        logs == null -> s.feedbackLogsFailed
        else -> s.feedbackLogsSize(MediaText.formatSize(logs.bytes.toLong()))
    }
}

private val BODY_MAX = 460.dp
