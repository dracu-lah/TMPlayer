package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.Tone

/**
 * Settings, then About, on the desktop: the same words and links as the phone and TV (they live
 * in [About]), drawn as the settings page draws its rows. The third-party notices open in the
 * page itself rather than in a browser, so they read the same offline.
 */
@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    var reading by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            PageHeader(
                title = if (reading) "Third-party notices" else "About",
                leading = {
                    IconButton(onClick = { if (reading) reading = false else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (reading) "Back to About" else "Back to settings",
                        )
                    }
                },
            )
            Column(
                Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reading) Notices() else Links(version, onRead = { reading = true })
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Links(version: String, onRead: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TMPlayer $version", style = MaterialTheme.typography.titleLarge)
            Text(About.LICENCE, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            Text(About.WARRANTY, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }

    About.groups(version).forEachIndexed { index, group ->
        Group(group.title)
        group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
        if (index == 0) {
            Setting("Third-party notices", "The libraries inside TMPlayer and their licences. Readable offline") {
                OutlinedButton(onClick = onRead) { Text("Read") }
            }
        }
        group.links.forEach { link ->
            Setting(link.title, link.detail.replace("this device", "this computer")) {
                OutlinedButton(onClick = { OpenExternal.browse(link.url) }) { Text("Open") }
            }
        }
    }
    Spacer(Modifier.size(24.dp))
}

@Composable
private fun Notices() {
    val type = MaterialTheme.typography
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            About.noticesBody.forEach { block ->
                when (block) {
                    is About.Block.Heading -> Text(
                        block.text,
                        style = if (block.level <= 2) type.titleMedium else type.titleSmall,
                        color = if (block.level == 1) Tone.text else Tone.accent,
                        modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 12.dp),
                    )
                    is About.Block.Paragraph -> Text(block.text, style = type.bodyMedium)
                    is About.Block.Bullet -> Row {
                        Text("•", style = type.bodyMedium, color = Tone.muted)
                        Spacer(Modifier.size(10.dp))
                        Text(block.text, style = type.bodyMedium)
                    }
                }
            }
        }
    }
}
