package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.tmplayer.desktop.LatestRelease
import com.tmplayer.ui.theme.Tone

/**
 * "TMPlayer 2.0.1 is out", in a corner, over the page and out of the way: it blocks nothing and
 * stays until the viewer picks Download (the release page in the browser) or Not now. Nothing is
 * downloaded or installed by the app.
 */
@Composable
fun UpdateNotice(release: LatestRelease, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = Tone.surfaceHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Text("TMPlayer ${release.version} is out", style = MaterialTheme.typography.titleSmall)
            Text(
                "Download it from the release page and install it over this one. Your sign in and downloads stay.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
            )
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onDownload) { Text("Download") }
            }
        }
    }
}
