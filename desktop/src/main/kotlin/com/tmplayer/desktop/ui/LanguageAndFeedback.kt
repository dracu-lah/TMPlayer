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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Feedback
import com.tmplayer.data.FeedbackReport
import com.tmplayer.data.FeedbackReports
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.SelfUpdate
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Background
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.FeedbackHost
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.rememberLanguageNotice
import com.tmplayer.ui.i18n.rememberWhatsNew
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.LanguageList
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                // Three columns where the window has room for names like "Español (Latinoamérica)" whole, two or one as it
                // narrows, so a name never breaks in the middle of a word.
                val density = androidx.compose.ui.platform.LocalDensity.current
                val windowDp = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
                LanguageList(settings, columns = languageColumns(windowDp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/** How many columns the language list gets in a window [window] wide: each cell wants about 230 dp at the usual font. */
internal fun languageColumns(window: androidx.compose.ui.unit.Dp): Int = when {
    window >= 1000.dp -> 3
    window >= 640.dp -> 2
    else -> 1
}

/** "What's new": the highlights of [WhatsNew.VERSION] and a link to the whole changelog. */
@Composable
fun WhatsNewPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.whatsnewTitle(WhatsNew.VERSION)) },
        text = {
            // Scrolls: the sheet has grown to four groups, and a small window must still reach Close.
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.whatsnewSubtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                for (section in WhatsNew.sections(DeviceForm.Desktop)) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                    for (line in section.lines) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(TmIcons.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(20.dp))
                            Text(line, style = MaterialTheme.typography.bodyLarge)
                        }
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
 * "Report a problem" and "Request a feature" on the desktop. With the build's inbox (HX64) the
 * report is written here, shown word for word and sent ([FeedbackHost]), with GitHub and e-mail
 * kept as the ways round. Without one (forks, CI): the prefilled GitHub page in the browser, an
 * email instead for somebody without a GitHub account, and the same page as a QR code for filing
 * it from a phone.
 */
@Composable
fun FeedbackPopup(onClose: () -> Unit, kind: FeedbackReport.Kind = FeedbackReport.Kind.Problem) {
    val s = LocalStrings.current
    val facts = remember { desktopFeedbackFacts() }
    val feature = kind == FeedbackReport.Kind.Feature
    val issue = remember(facts, feature) { if (feature) Feedback.featureUrl(facts) else Feedback.issueUrl(facts) }
    if (FeedbackReports.available) {
        FeedbackHost(
            kind = kind,
            facts = facts,
            onDone = onClose,
            onGithub = { OpenExternal.browse(issue) },
            onEmail = { OpenExternal.mail(Feedback.mailto(facts, feature)) },
        )
        return
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = issue) {
        value = withContext(Dispatchers.Default) { QrCode.render(issue, 400) }
    }
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(TmIcons.Bug, contentDescription = null, tint = Tone.accent) },
        title = { Text(if (feature) s.feedbackFeatureTitle else s.feedbackTitle) },
        text = {
            Row(Modifier.widthIn(max = 640.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (feature) s.feedbackFeatureBodyDesktop else s.feedbackBodyDesktop, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { OpenExternal.browse(issue); onClose() }) { Text(s.feedbackOpenGithub) }
                    OutlinedButton(onClick = { OpenExternal.mail(Feedback.mailto(facts, feature)); onClose() }) { Text(s.feedbackEmail) }
                    Text(s.feedbackEmailDetail(Feedback.EMAIL), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
                Box(
                    Modifier.size(180.dp).clip(RoundedCornerShape(Corner.Large)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it, contentDescription = if (feature) s.feedbackFeatureQr else s.feedbackQr, modifier = Modifier.fillMaxSize().padding(8.dp)) }
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
            Icon(TmIcons.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
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
 * A Windows update that did not install (issue #7): its script left a marker, and this says so
 * once, with the way to the download page.
 */
@Composable
fun UpdateFailedCard(version: String, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.width(420.dp).floatingSurface().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TmIcons.Info, contentDescription = null, tint = Tone.caution, modifier = Modifier.size(22.dp))
            Text(s.updateDidNotFinish, style = MaterialTheme.typography.titleMedium)
        }
        Text(s.updateDidNotInstall(version), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(s.commonDismiss) }
            Button(onClick = onOpen) { Text(s.commonOpen) }
        }
    }
}

/**
 * The failed update card, the language card and "What's new", over the signed in window and never
 * over the player. The cards wait for the update popup; "What's new" waits for it too, and shows
 * once per version.
 */
@Composable
fun ShellNotices(state: ShellState, modifier: Modifier = Modifier) {
    val playing = state.playerUp
    val failed by produceState<String?>(null) { value = withContext(Dispatchers.IO) { SelfUpdate.failedUpdate() } }
    var failedSeen by remember { mutableStateOf(false) }
    val failedVersion = failed?.takeUnless { failedSeen }
    if (failedVersion != null && !playing && !state.updatePopup) {
        fun close() {
            failedSeen = true
            Background.scope.launch(Dispatchers.IO) { SelfUpdate.dismissFailedUpdate() }
        }
        UpdateFailedCard(
            version = failedVersion,
            onOpen = {
                OpenExternal.browse(SelfUpdate.DOWNLOAD_PAGE)
                close()
            },
            onDismiss = ::close,
            modifier = modifier,
        )
        return
    }
    val notice = rememberLanguageNotice(state.settings)
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
    // The quick tour as a Tips card (R3), once, behind the other notices: not a dialog. Show me
    // opens the tour over the window (Shell.kt); Not now turns the card off for good.
    val tour by state.settings.tourState.collectAsState(initial = SettingsStore.TourState.Done)
    if (SettingsStore.tipsDue(tour) && language == null && !whatsNew.showing && !playing && !state.updatePopup) {
        val scope = rememberCoroutineScope()
        com.tmplayer.ui.onboarding.TipsCard(
            onShow = { scope.launch { runCatching { state.settings.openTour() } } },
            onDismiss = { scope.launch { runCatching { state.settings.markTourSkipped() } } },
            modifier = modifier.widthIn(max = 640.dp),
        )
    }
}

