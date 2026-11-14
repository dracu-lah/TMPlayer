package com.tmplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CrashAsk
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.platform.Background
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.tvHoldsFocus
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one-time "Help fix crashes?" card (S1) over the chat list, on the phone, the television and
 * the desktop alike, for an install that was never asked ([CrashAsk]). It does not block: the page
 * behind it still works, and on a phone or a desktop nothing moves the focus. A television's
 * remote lands on Yes ([CrashAsk.firstFocus]) and is kept in the card until it is answered; Back
 * there is No ([CrashAsk.BACK]).
 *
 * Yes and No look the same: it is consent, so nothing marks one answer as the better one. The
 * privacy page opens in the browser, or as a QR code on a television ([PrivacyQr]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashAskCard(onAnswer: (yes: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val uri = LocalUriHandler.current
    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    var qr by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val firstControl = CrashAsk.firstFocus(DeviceForm.Tv)
    // Back is No ([CrashAsk.BACK]): leaving the question is never consent.
    if (tv) BackHandler { onAnswer(false) }
    Column(
        modifier
            .tvHoldsFocus(first)
            .widthIn(max = if (tv) 560.dp else 520.dp)
            .floatingSurface()
            .padding(if (tv) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.setupCrashAskTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        // A computer's report names the operating system, not a device or a video (CP102g).
        val body = if (form == DeviceForm.Desktop) s.setupCrashAskBodyDesktop else s.setupCrashAskBody
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        // Wraps rather than squeezes a long translation; in reading order, which is the order a
        // remote or Tab walks.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (control in CrashAsk.ORDER) {
                val focus = if (control == firstControl) Modifier.focusRequester(first) else Modifier
                when (control) {
                    CrashAsk.Control.Yes -> SetupButton(s.setupCrashAskYes, modifier = focus) { onAnswer(true) }
                    CrashAsk.Control.No -> SetupButton(s.setupCrashAskNo, modifier = focus) { onAnswer(false) }
                    CrashAsk.Control.Privacy -> SetupButton(s.setupCrashPrivacy, modifier = focus) {
                        qr = when {
                            qr -> false
                            tv -> true
                            else -> runCatching { uri.openUri(About.PRIVACY) }.isFailure
                        }
                    }
                }
            }
        }
        if (qr) PrivacyQr()
    }
}

/**
 * [CrashAskCard], wired: shown when [CrashAsk.decide] says so, marked asked as it first shows,
 * and the answer written to Settings' "Send crash reports" switch, then [reporting] started or
 * stopped. [inShell] is the chat list up with nothing covering it, [otherUp] any other card or
 * dialog, [safeMode] a safe start after crashes. Nothing at all in a build that cannot report.
 */
@Composable
fun CrashAskHost(
    settings: SettingsStore,
    reporting: CrashReporting,
    inShell: Boolean,
    otherUp: Boolean,
    modifier: Modifier = Modifier,
    safeMode: Boolean = false,
) {
    if (!reporting.available) return
    // True until read, so a slow read never flashes the card at somebody who answered long ago.
    val asked by settings.crashReportsAsked.collectAsState(initial = true)
    val lock by settings.appLock.collectAsState(initial = null)
    var shown by rememberSaveable { mutableStateOf(false) }
    var answered by rememberSaveable { mutableStateOf(false) }
    val kids = lock?.kids ?: return
    val decision = CrashAsk.decide(
        CrashAsk.Inputs(
            available = reporting.available,
            asked = asked,
            inShell = inShell,
            kidsLock = kids,
            safeMode = safeMode,
            otherUp = otherUp,
            shownThisRun = shown,
            answeredThisRun = answered,
        ),
    )
    val firstShow = decision == CrashAsk.Decision.ShowAndMarkAsked
    LaunchedEffect(firstShow) {
        if (!firstShow) return@LaunchedEffect
        shown = true
        // Not cancelled by the card going away under it: the question was put either way.
        withContext(NonCancellable) { runCatching { settings.markCrashReportsAsked() } }
    }
    if (decision == CrashAsk.Decision.Hide) return
    CrashAskCard(
        onAnswer = { yes ->
            answered = true
            // Outlives the card, which leaves with this press.
            Background.scope.launch {
                runCatching { settings.setCrashReports(yes) }
                runCatching { reporting.onChange(yes) }
            }
        },
        modifier = modifier,
    )
}
