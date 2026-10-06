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
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * Settings, then About, on the desktop: the same words and links as the phone and TV (they live
 * in [About]), drawn as the settings page draws its rows. The third-party notices open in the
 * page itself rather than in a browser, so they read the same offline.
 */
@Composable
fun AboutPage(version: String, supporter: Boolean = false, onBack: () -> Unit) {
    val s = LocalStrings.current
    var reading by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            PageHeader(
                title = if (reading) s.aboutNoticesTitle else s.aboutTitleShort,
                leading = {
                    IconButton(onClick = { if (reading) reading = false else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (reading) s.aboutBackToAbout else s.aboutBackToSettings,
                        )
                    }
                },
            )
            Column(
                Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reading) Notices() else Links(version, supporter, onRead = { reading = true })
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Links(version: String, supporter: Boolean, onRead: () -> Unit) {
    val s = LocalStrings.current
    var supporting by remember { mutableStateOf(false) }
    if (supporting) SupportPopup(from = "about", onClose = { supporting = false })
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.settingsAppVersion(version), style = MaterialTheme.typography.titleLarge)
            Text(About.LICENCE, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            Text(About.WARRANTY, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }

    About.groups(version, supporter = supporter).forEachIndexed { index, group ->
        Group(group.title)
        // TMDB's logo with its notice, smaller than TMPlayer's own mark.
        if (group.tmdbLogo) com.tmplayer.ui.about.TmdbMark(Modifier.padding(bottom = 6.dp))
        group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
        if (index == 0) {
            Setting(s.aboutNoticesTitle, s.aboutNoticesDetail) {
                OutlinedButton(onClick = onRead) { Text(s.aboutRead) }
            }
        }
        if (group.support) {
            Setting(s.aboutShowQr, s.aboutShowQrDetail) {
                OutlinedButton(onClick = { supporting = true }) { Text(s.commonShow) }
            }
        }
        group.links.forEach { link ->
            Setting(link.title, if (link.url == About.PRIVACY) s.settingsPrivacyComputer else link.detail) {
                OutlinedButton(onClick = { OpenExternal.browse(link.url) }) { Text(s.commonOpen) }
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
