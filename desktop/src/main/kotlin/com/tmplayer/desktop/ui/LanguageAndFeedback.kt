package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Feedback
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.SelfUpdate
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.rememberLanguageNotice
import com.tmplayer.ui.i18n.rememberWhatsNew
import com.tmplayer.ui.onboarding.LanguageList
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings, then Language, on the desktop: every language named in itself in three columns, the
 * app switching under the open popup the moment one is picked.
 */
@Composable
fun LanguagePopup(settings: SettingsStore, onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.languagePickerTitle) },
        text = {
            Column(
                Modifier.widthIn(max = 720.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.languagePickerNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                LanguageList(settings, columns = 3)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/** "What's new": the highlights of [WhatsNew.VERSION] and a link to the whole changelog. */
@Composable
fun WhatsNewPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.whatsnewTitle(WhatsNew.VERSION)) },
        text = {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.whatsnewSubtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                for (line in WhatsNew.highlights) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(20.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { OpenExternal.browse(WhatsNew.CHANGELOG) }) { Text(s.whatsnewFullChangelog) } },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/** What a report from this computer says about it: the system and how TMPlayer was installed. */
fun desktopFeedbackFacts(): Feedback.Facts {
    val platform = when (OsInfo.osTag) {
        "windows" -> Feedback.Platform.Windows
        "linux" -> Feedback.Platform.Linux
        else -> Feedback.Platform.Other
    }
    val system = linuxPrettyName() ?: "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    val install = runCatching { SelfUpdate.detect() }.getOrNull()
    return Feedback.Facts(
        version = BuildInfo.VERSION,
        platform = platform,
        device = listOfNotNull(system, install?.let { reportName(it) }).joinToString(", "),
        language = Translator.messages.tag,
    )
}

/** How the bug template asks for the install: "AppImage", "tarball or manual", "MSI". */
private fun reportName(kind: InstallKind): String = when (kind) {
    InstallKind.WindowsMsi -> "MSI"
    InstallKind.WindowsPortable -> "portable zip" // i18n-ok: for the maintainer, in English
    InstallKind.AppImage -> "AppImage" // i18n-ok: for the maintainer, in English
    InstallKind.Deb -> "deb"
    InstallKind.Rpm -> "rpm"
    InstallKind.Flatpak -> "Flatpak" // i18n-ok: for the maintainer, in English
    InstallKind.Manual -> "tarball, AUR or a build" // i18n-ok: for the maintainer, in English
}

/** `PRETTY_NAME` from /etc/os-release, "Fedora Linux 43 (Workstation Edition)". */
private fun linuxPrettyName(): String? {
    if (OsInfo.osTag != "linux") return null
    return runCatching {
        File("/etc/os-release").readLines()
            .firstOrNull { it.startsWith("PRETTY_NAME=") } // i18n-ok: an os-release field name
            ?.substringAfter('=')?.trim('"')
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * "Report a problem" on the desktop: the prefilled bug report in the browser, an email instead
 * for somebody without a GitHub account, and the same report as a QR code for filing it from a
 * phone.
 */
@Composable
fun FeedbackPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    val facts = remember { desktopFeedbackFacts() }
    val issue = remember(facts) { Feedback.issueUrl(facts) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = issue) {
        value = withContext(Dispatchers.Default) { QrCode.render(issue, 400) }
    }
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(com.tmplayer.ui.components.TmIcons.Bug, contentDescription = null, tint = Tone.accent) },
        title = { Text(s.feedbackTitle) },
        text = {
            Row(Modifier.widthIn(max = 640.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.feedbackBodyDesktop, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { OpenExternal.browse(issue); onClose() }) { Text(s.feedbackOpenGithub) }
                    OutlinedButton(onClick = { OpenExternal.mail(Feedback.mailto(facts)); onClose() }) { Text(s.feedbackEmail) }
                    Text(s.feedbackEmailDetail(Feedback.EMAIL), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
                Box(
                    Modifier.size(180.dp).clip(RoundedCornerShape(Corner.Large)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it, contentDescription = s.feedbackQr, modifier = Modifier.fillMaxSize().padding(8.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/**
 * The one-time "Now in Español" card in the window's corner, like the support card: the app has
 * followed the system into a language, and this says so, in it, once.
 */
@Composable
fun LanguageNoticeCard(language: String, onKeep: () -> Unit, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.width(420.dp).floatingSurface().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
            Text(s.languageNowIn(LanguageNotice.name(language)), style = MaterialTheme.typography.titleMedium)
        }
        Text(s.languageNowInBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = onChange) { Text(s.commonChange) }
            Button(onClick = onKeep) { Text(s.languageKeep) }
        }
    }
}

/**
 * The one card after the first sign in (CP42, see `FirstSignIn`): "Show everything" keeps the side
 * bar as it is, "Only my folders" goes on to the same confirm prompt as the Settings switch. Drawn
 * where the language card is, and like it.
 */
@Composable
fun FirstSignInCard(onEverything: () -> Unit, onOnlyFolders: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.width(440.dp).floatingSurface().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(com.tmplayer.ui.components.TmIcons.Folder, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
            Text(s.groupsCardTitle, style = MaterialTheme.typography.titleMedium)
        }
        Text(s.groupsCardBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = onOnlyFolders) { Text(s.groupsCardFolders) }
            Button(onClick = onEverything) { Text(s.groupsCardEverything) }
        }
    }
}

/**
 * The language card and "What's new", over the signed in window and never over the player. The
 * card waits for the update popup; "What's new" waits for it too, and shows once per version.
 */
@Composable
fun ShellNotices(state: ShellState, modifier: Modifier = Modifier) {
    val notice = rememberLanguageNotice(state.settings)
    val playing = state.nowPlaying != null
    val whatsNew = rememberWhatsNew(state.settings, BuildInfo.VERSION, hold = playing || state.updatePopup)
    var picking by remember { mutableStateOf(false) }
    val language = notice.language
    if (language != null && !playing && !state.updatePopup) {
        LanguageNoticeCard(
            language = language,
            onKeep = notice.dismiss,
            onChange = {
                notice.dismiss()
                picking = true
            },
            modifier = modifier,
        )
    }
    if (picking) LanguagePopup(state.settings, onClose = { picking = false })
    if (whatsNew.showing && !playing && !state.updatePopup) WhatsNewPopup(onClose = { whatsNew.showing = false })
}

