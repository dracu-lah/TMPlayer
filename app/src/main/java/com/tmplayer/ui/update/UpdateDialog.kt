package com.tmplayer.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.UpdateState
import com.tmplayer.data.Updates
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.ignoreStrayRelease
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton

/**
 * What happens after the viewer presses Update: confirm, download, then Android takes over.
 *
 * Both the rail and Settings show this same dialog, because [Updates] holds the state and this is
 * only a window onto it. It closes itself once the installer is on screen: what happens next is
 * the system's business, and coming back to a spent dialog would be confusing.
 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsStateWithLifecycle()
    val confirm = remember { FocusRequester() }

    // The TV has to be told, once, that installs from TMPlayer are allowed. Android will not take
    // that answer from in here, so the viewer is sent to the switch and comes back with Back.
    val allowed = Updates.canInstall(context)

    val release = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Ready -> current.release
        else -> null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .ignoreStrayRelease()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, Caution.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Caution,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        when {
                            release == null -> "TMPlayer is up to date"
                            state is UpdateState.Downloading -> "Downloading ${release.version}"
                            else -> "Update to ${release.version}"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                }

                Text(
                    body(state, allowed, release?.sizeBytes ?: 0L),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMuted,
                )

                (state as? UpdateState.Downloading)?.let { downloading ->
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(downloading.fraction)
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        state is UpdateState.Downloading -> Unit

                        release != null && !allowed -> TmButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Updates.unknownSourcesIntent(context))
                                }
                            },
                            modifier = Modifier.focusRequester(confirm),
                        ) { Text("Open that setting") }

                        release != null -> TmButton(
                            onClick = { scope.launch { Updates.downloadAndInstall(context, release) } },
                            modifier = Modifier.focusRequester(confirm),
                        ) { Text("Download and install") }

                        else -> TmButton(
                            onClick = { scope.launch { Updates.check() } },
                            modifier = Modifier.focusRequester(confirm),
                        ) { Text("Check again") }
                    }

                    if (state !is UpdateState.Downloading) {
                        TmSecondaryButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }

    // Handing over to the installer is the end of this dialog's job.
    LaunchedEffect(state) {
        if (state is UpdateState.Ready) onDismiss()
    }
    LaunchedEffect(Unit) { runCatching { confirm.requestFocus() } }
}

/**
 * A plain bar, filled as far as the download has come.
 *
 * An unknown length shows as a full-width trough with nothing in it rather than as a spinner:
 * this dialog already says what it is doing in words, and a second moving thing is noise.
 */
@Composable
private fun ProgressBar(fraction: Float?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TextMuted.copy(alpha = 0.25f)),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Caution),
            )
        }
    }
}

private fun body(state: UpdateState, allowed: Boolean, sizeBytes: Long): String = when {
    state is UpdateState.Failed -> state.message
    state is UpdateState.Checking -> "Asking GitHub…"
    state is UpdateState.Downloading -> "From the project's GitHub releases. Keep this on screen."
    state is UpdateState.Available && !allowed ->
        "This TV blocks installs from TMPlayer until you say otherwise. Turn on \"allow apps " +
            "from this source\", press Back to come here again, then start the update."
    state is UpdateState.Available ->
        "TMPlayer downloads it (${StreamStats.formatBytes(sizeBytes)}) from the project's GitHub " +
            "releases, then Android asks you to confirm the install. Everything you have watched " +
            "and starred stays where it is."
    else -> "You are on ${Updates.installedVersion}, the newest release on " +
        "${Updates.RELEASES_PAGE}."
}
