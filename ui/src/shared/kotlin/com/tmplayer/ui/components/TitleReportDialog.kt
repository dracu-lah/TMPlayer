package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.TitleReports
import com.tmplayer.data.TitleSender.State
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * "Wrong title?" (N1) on the phone and the desktop: the pair that would be sent, word for word,
 * two ticks that start off, Send, and No thanks. The television draws [TitleReportBody] in its own
 * panel (`TitleReportTvPanel`), so the words and the order of everything are the same on all three.
 *
 * Send works only once the first tick is on; the chat's name goes only if the second is. A step
 * after Send says what happened (the code, kept for later, not taken) and has Close alone.
 */
@Composable
fun TitleReportDialog(
    state: State,
    onAgree: (Boolean) -> Unit,
    onIncludeChat: (Boolean) -> Unit,
    onSend: () -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val review = state as? State.Review
    val quiet = remember { FocusRequester() }
    TmAlertDialog(
        // A tap beside it, or Back, is "No thanks" on the question, and Close after it (Cancel while it sends).
        onDismissRequest = { if (review != null) onDecline() else onClose() },
        title = { Text(titleReportHeading(s, state), maxLines = 2) },
        text = {
            Column(
                Modifier.heightIn(max = dialogBodyMax(BODY_MAX)).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TitleReportBody(state, onAgree, onIncludeChat)
            }
        },
        confirmButton = {
            when {
                review != null -> TextButton(onClick = onSend, enabled = review.canSend) { Text(s.titlesSend) }
                state is State.Sending -> Unit
                else -> TextButton(onClick = onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonClose) }
            }
        },
        dismissButton = {
            when {
                review != null -> TextButton(onClick = onDecline, modifier = Modifier.focusRequester(quiet)) { Text(s.titlesNoThanks) }
                state is State.Sending -> TextButton(onClick = onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonCancel) }
            }
        },
    )
    // The safe press is under the keyboard from the start: nothing leaves by an accidental Enter.
    LaunchedEffect(state::class) { runCatching { quiet.requestFocus() } }
}

/** What the viewer can do at each step, so the phone's dialog and the television's panel share the host. */
class TitleReportActions(
    val onAgree: (Boolean) -> Unit,
    val onIncludeChat: (Boolean) -> Unit,
    val onSend: () -> Unit,
    val onDecline: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The one place the app draws the title question, once, over everything: it follows the process's
 * sender ([TitleReports]), so a rename that closes its page still gets its question. Nothing is
 * drawn without an inbox or between steps. [tvPanel] draws it for a remote (the television's own
 * panel); left out, the dialog is used.
 */
@Composable
fun TitleReportHost(tvPanel: (@Composable (State, TitleReportActions) -> Unit)? = null) {
    val sender = TitleReports.sender ?: return
    val state by sender.state.collectAsState()
    if (state is State.Idle || state is State.Preparing) return
    val actions = remember(sender) {
        TitleReportActions(
            onAgree = sender::setAgreed,
            onIncludeChat = sender::setIncludeChat,
            onSend = { sender.send(com.tmplayer.platform.Background.scope) },
            onDecline = { sender.decline(com.tmplayer.platform.Background.scope) },
            onClose = sender::close,
        )
    }
    if (tvPanel != null && !isTouch()) {
        tvPanel(state, actions)
    } else {
        TitleReportDialog(state, actions.onAgree, actions.onIncludeChat, actions.onSend, actions.onDecline, actions.onClose)
    }
}

/**
 * The words of each step, in the order every platform shows them. [onAgree] and [onIncludeChat]
 * are the two ticks of the review; the other steps have none.
 */
@Composable
fun TitleReportBody(state: State, onAgree: (Boolean) -> Unit, onIncludeChat: (Boolean) -> Unit) {
    val s = LocalStrings.current
    when (state) {
        is State.Review -> {
            val report = state.report
            Text(s.titlesBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            // Exactly what leaves, as the scrub made it: the viewer sees a phone number gone from the name.
            TitleReportLine(s.titlesFileName, report?.fileName.orEmpty())
            TitleReportLine(s.titlesTitleChosen, report?.title.orEmpty())
            TitleTick(s.titlesAgree, state.agreed, onAgree)
            state.chatOffer?.let { chat -> TitleTick(s.titlesChatTick(chat), state.includeChat, onIncludeChat) }
        }
        is State.Sending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 3.dp, color = Tone.accent)
            Text(s.titlesSending, style = MaterialTheme.typography.bodyMedium)
        }
        is State.Sent -> {
            // Large and in a monospace face, so a 0 and an O are not confused and a television can be read across the room.
            Text(
                state.code,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    letterSpacing = 3.sp,
                    textAlign = TextAlign.Center,
                ),
                color = Tone.accent,
            )
            Text(
                if (state.duplicate) s.titlesSentDupBody else s.titlesSentBody(KEEP_DAYS),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
            )
        }
        is State.Queued -> Text(if (state.waitSeconds > 0) s.titlesKeptSlow else s.titlesKept, style = MaterialTheme.typography.bodyMedium)
        is State.Refused -> Text(s.titlesRefused, style = MaterialTheme.typography.bodyMedium)
        is State.AlreadySent -> Text(s.titlesAlreadySent, style = MaterialTheme.typography.bodyMedium)
        State.Idle, State.Preparing -> Unit
    }
}

/** The step's heading: the question while reviewing, then what happened. */
fun titleReportHeading(s: Strings, state: State): String = when (state) {
    is State.Sent -> s.titlesSentTitle
    is State.Queued -> s.titlesKeptTitle
    is State.Refused -> s.titlesRefusedTitle
    is State.AlreadySent -> s.titlesSentTitle
    else -> s.titlesTitle
}

/** One line of the pair, labelled, in a box so the viewer sees where each begins and ends. */
@Composable
fun TitleReportLine(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
    }
}

/** A tick box whose whole row is the target, with the app's focus ring so a remote or a keyboard sees where it is. At least 48 dp tall. */
@Composable
fun TitleTick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tick = rememberToggleTick()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Small)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .focusRing(focused, shape, filled = false)
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Checkbox,
                onValueChange = { tick(it); onChange(it) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
    }
}

/** How long the inbox keeps a title report, as the Worker's own constant. */
private const val KEEP_DAYS = 30

private val BODY_MAX = 380.dp

