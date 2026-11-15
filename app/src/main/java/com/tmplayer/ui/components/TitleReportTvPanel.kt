package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.TitleSender.State
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface

/**
 * "Wrong title?" (N1) under a remote: the same words as the phone's dialog ([TitleReportBody]) in
 * the television's own panel. Nothing is typed. The remote starts on No thanks, the press that
 * changes nothing; Up walks the two ticks, and Send works only once the first is on. After a send
 * the short code is shown large, for the viewer to read out.
 */
@Composable
fun TitleReportTvPanel(state: State, actions: TitleReportActions) {
    val review = state is State.Review
    // Back is No thanks on the question, and Close after it (Cancel while it sends).
    FloatingWindow(onDismiss = { if (review) actions.onDecline() else actions.onClose() }, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, 680.dp)
        TitleReportTvContent(state, actions, Modifier.width(panel))
    }
}

/** The panel's contents, apart from its window so a test can drive them by remote. */
@Composable
internal fun TitleReportTvContent(state: State, actions: TitleReportActions, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val review = state as? State.Review
    val quiet = remember { FocusRequester() }
    Column(
        modifier.floatingSurface().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(titleReportHeading(s, state), style = MaterialTheme.typography.headlineSmall, color = Tone.text)
        TitleReportBody(state, actions.onAgree, actions.onIncludeChat)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            when {
                review != null -> {
                    TmSecondaryButton(onClick = actions.onDecline, modifier = Modifier.focusRequester(quiet)) { Text(s.titlesNoThanks) }
                    TmButton(onClick = actions.onSend, enabled = review.canSend) { Text(s.titlesSend) }
                }
                state is State.Sending -> TmSecondaryButton(onClick = actions.onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonCancel) }
                else -> TmButton(onClick = actions.onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonClose) }
            }
        }
    }
    // The safe press is focused at each step: a stray OK from the press that opened this never sends.
    LaunchedEffect(state::class) { runCatching { quiet.requestFocus() } }
}
