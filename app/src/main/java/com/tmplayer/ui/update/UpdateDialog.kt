package com.tmplayer.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Release
import com.tmplayer.data.UpdateState
import com.tmplayer.data.Updates
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.ignoreStrayRelease
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.paneAction

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
    val touch = isTouch()

    // The TV has to be told, once, that installs from TMPlayer are allowed. Android will not take
    // that answer from in here, so the viewer is sent to the switch and comes back with Back.
    val allowed = Updates.canInstall(context)

    val release = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Ready -> current.release
        else -> null
    }

    if (touch) {
        TouchUpdateDialog(
            state = state,
            release = release,
            allowed = allowed,
            onPrimary = {
                when {
                    release != null && !allowed -> runCatching {
                        context.startActivity(Updates.unknownSourcesIntent(context))
                    }
                    release != null -> scope.launch { Updates.downloadAndInstall(context, release) }
                    else -> scope.launch { Updates.check() }
                }
            },
            onDismiss = onDismiss,
        )
        LaunchedEffect(state) {
            if (state is UpdateState.Ready) onDismiss()
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .ignoreStrayRelease()
                .background(Color.Black.copy(alpha = 0.82f))
                // A dialog is its own window and is handed the whole screen, insets included, so
                // on a phone this panel has to keep clear of the status bar and gesture handle
                // itself.
                .then(if (touch) Modifier.safeDrawingPadding() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // A ceiling rather than a width: 620dp is a comfortable paragraph on a television and
            // half again the width of a phone held upright.
            val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)

            // Written once and placed in whichever direction the device wants them. A phone
            // stacks them full width under the thumb; a remote steps along a row.
            val buttons: @Composable () -> Unit = {
                when {
                    state is UpdateState.Downloading -> Unit

                    release != null && !allowed -> TmButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Updates.unknownSourcesIntent(context))
                            }
                        },
                        modifier = Modifier.focusRequester(confirm).paneAction(),
                    ) { Text("Open that setting") }

                    release != null -> TmButton(
                        onClick = { scope.launch { Updates.downloadAndInstall(context, release) } },
                        modifier = Modifier.focusRequester(confirm).paneAction(),
                    ) { Text("Download and install") }

                    else -> TmButton(
                        onClick = { scope.launch { Updates.check() } },
                        modifier = Modifier.focusRequester(confirm).paneAction(),
                    ) { Text("Check again") }
                }

                if (state !is UpdateState.Downloading) {
                    TmSecondaryButton(
                        onClick = onDismiss,
                        modifier = Modifier.paneAction(),
                    ) { Text("Close") }
                }
            }

            Column(
                Modifier
                    .width(panel)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, Caution.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(if (touch) 22.dp else 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Caution,
                        modifier = Modifier.size(if (touch) 24.dp else 28.dp),
                    )
                    Spacer(Modifier.width(if (touch) 10.dp else 14.dp))
                    Text(
                        when {
                            release == null -> "TMPlayer is up to date"
                            state is UpdateState.Downloading -> "Downloading ${release.version}"
                            else -> "Update to ${release.version}"
                        },
                        // A sofa-sized heading over a version number wraps on a phone.
                        style = if (touch) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        color = TextPrimary,
                    )
                }

                Text(
                    body(state, allowed, release?.sizeBytes ?: 0L, if (touch) "phone" else "TV"),
                    style = if (touch) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = TextMuted,
                )

                (state as? UpdateState.Downloading)?.let { downloading ->
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(downloading.fraction)
                }

                Spacer(Modifier.height(10.dp))
                if (touch) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
                }
            }
        }
    }

    // Handing over to the installer is the end of this dialog's job.
    LaunchedEffect(state) {
        if (state is UpdateState.Ready) onDismiss()
    }
    // Only a remote needs a starting point. A finger picks its own, and focus parked on the
    // primary button is a ring a phone viewer has no way to explain.
    if (!touch) {
        LaunchedEffect(Unit) { runCatching { confirm.requestFocus() } }
    }
}

/** As wide as this dialog ever gets, on any screen. */
private val PANEL_MAX = 620.dp

/**
 * The phone's version: Material's own dialog, in place of the hand-built panel.
 *
 * The panel was drawn for a television, where a dialog is a lit card in the middle of a dark room
 * and every control has to be reachable by D-pad. On a phone all of that is already decided by the
 * platform: the scrim, the width, the corner radius, the button order, tap-outside to dismiss and
 * the announcement a screen reader makes on the way in. Writing them again by hand only meant
 * getting them slightly wrong, and the sizes were the sofa's.
 */
@Composable
private fun TouchUpdateDialog(
    state: UpdateState,
    release: Release?,
    allowed: Boolean,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloading = state as? UpdateState.Downloading
    AlertDialog(
        // A download in progress is the one state that must not be dismissed by a stray tap
        // outside it: the dialog is what is holding the download's own progress on screen.
        onDismissRequest = { if (downloading == null) onDismiss() },
        icon = {
            M3Icon(Icons.Filled.Refresh, contentDescription = null, tint = Caution)
        },
        title = {
            M3Text(
                when {
                    release == null -> "TMPlayer is up to date"
                    downloading != null -> "Downloading ${release.version}"
                    else -> "Update to ${release.version}"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                M3Text(body(state, allowed, release?.sizeBytes ?: 0L, "phone"))
                if (downloading != null) {
                    // An unknown length becomes the indeterminate bar rather than an empty
                    // trough, which is what the platform's own control is for.
                    if (downloading.fraction != null) {
                        LinearProgressIndicator(
                            progress = { downloading.fraction.coerceIn(0f, 1f) },
                            color = Caution,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Caution,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (downloading != null) return@AlertDialog
            TextButton(onClick = onPrimary) {
                M3Text(
                    when {
                        release != null && !allowed -> "Open that setting"
                        release != null -> "Download and install"
                        else -> "Check again"
                    },
                )
            }
        },
        dismissButton = {
            if (downloading != null) return@AlertDialog
            TextButton(onClick = onDismiss) { M3Text("Close") }
        },
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted,
    )
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

/** @param device what to call the machine this is running on, which the install notice names. */
private fun body(
    state: UpdateState,
    allowed: Boolean,
    sizeBytes: Long,
    device: String,
): String = when {
    state is UpdateState.Failed -> state.message
    state is UpdateState.Checking -> "Asking GitHub…"
    state is UpdateState.Downloading -> "From the project's GitHub releases. Keep this on screen."
    state is UpdateState.Available && !allowed ->
        "This $device blocks installs from TMPlayer until you say otherwise. Turn on \"allow apps " +
            "from this source\", press Back to come here again, then start the update."
    state is UpdateState.Available ->
        "TMPlayer downloads it (${StreamStats.formatBytes(sizeBytes)}) from the project's GitHub " +
            "releases, then Android asks you to confirm the install. Everything you have watched " +
            "and starred stays where it is."
    else -> "You are on ${Updates.installedVersion}, the newest release on " +
        "${Updates.RELEASES_PAGE}."
}
