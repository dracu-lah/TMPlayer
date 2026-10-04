package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.LatestRelease
import com.tmplayer.desktop.UpdateProgress
import com.tmplayer.ui.theme.Tone

/**
 * "TMPlayer 2.0.1 is out", in a corner, over the page and out of the way: it blocks nothing and
 * stays until the viewer acts on it.
 *
 * Where this install can update itself ([canUpdate]) the main button is Update now, and the notice
 * follows the download, the check and the install through to Restart now. Elsewhere (Flatpak, the
 * tarball) Download opens the release page, as before.
 */
@Composable
fun UpdateNotice(
    release: LatestRelease,
    canUpdate: Boolean,
    kind: InstallKind,
    progress: UpdateProgress,
    onUpdate: () -> Unit,
    onRestart: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = Tone.surfaceHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            val title = if (progress is UpdateProgress.Ready) "TMPlayer ${progress.version} is ready" else "TMPlayer ${release.version} is out"
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                updateMessage(canUpdate, kind, progress),
                style = MaterialTheme.typography.bodySmall,
                color = if (progress is UpdateProgress.Failed) Tone.danger else Tone.muted,
                modifier = Modifier.padding(end = 8.dp),
            )
            val bar = Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp, bottom = 12.dp)
            when (progress) {
                is UpdateProgress.Downloading -> {
                    val fraction = progress.fraction
                    if (fraction == null) LinearProgressIndicator(bar) else LinearProgressIndicator({ fraction }, bar)
                }
                UpdateProgress.Verifying, UpdateProgress.Installing -> LinearProgressIndicator(bar)
                else -> Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        progress is UpdateProgress.Ready -> {
                            TextButton(onClick = onDismiss) { Text("Later") }
                            TextButton(onClick = onRestart) { Text("Restart now") }
                        }
                        canUpdate -> {
                            TextButton(onClick = onDismiss) { Text("Not now") }
                            if (progress is UpdateProgress.Failed) TextButton(onClick = onDownload) { Text("Release page") }
                            TextButton(onClick = onUpdate) { Text(if (progress is UpdateProgress.Failed) "Try again" else "Update now") }
                        }
                        else -> {
                            TextButton(onClick = onDismiss) { Text("Not now") }
                            TextButton(onClick = onDownload) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}

internal fun updateMessage(canUpdate: Boolean, kind: InstallKind, progress: UpdateProgress): String = when (progress) {
    is UpdateProgress.Downloading -> "Downloading" + (progress.fraction?.let { " ${(it * 100).toInt()}%" } ?: "")
    UpdateProgress.Verifying -> "Checking the download against the release checksums"
    UpdateProgress.Installing -> "Installing. Your system may ask for your password"
    is UpdateProgress.Ready -> when (kind) {
        InstallKind.WindowsMsi, InstallKind.WindowsPortable -> "TMPlayer closes, installs the update and opens again."
        else -> "Restart TMPlayer to use it. Your sign in and downloads stay."
    }
    is UpdateProgress.Failed -> progress.message
    UpdateProgress.Idle -> when {
        canUpdate && (kind == InstallKind.Deb || kind == InstallKind.Rpm) ->
            "Update in place. Installing a ${kind.label} asks for your password."
        canUpdate -> "Update in place. Your sign in and downloads stay."
        kind == InstallKind.Flatpak -> "Download the new Flatpak from the release page and install it over this one."
        else -> "Download it from the release page and install it over this one. Your sign in and downloads stay."
    }
}
