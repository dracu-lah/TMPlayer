package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * What the shell shows for a [PlayRequest] until the player (com.tmplayer.desktop.player) is
 * passed to [DesktopShell] in its place.
 */
@Composable
fun PlayerPlaceholder(request: PlayRequest, onClose: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize()) {
        IconButton(onClick = onClose, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.commonBack)
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(s.playerPlaceholder, style = MaterialTheme.typography.headlineSmall)
            Text(request.item.title, color = Tone.muted)
            Text(
                if (request.startFromBeginning) s.playerFromStart else s.playerPlaceholderResume,
                color = Tone.muted,
            )
        }
    }
}
