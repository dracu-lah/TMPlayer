package com.tmplayer.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.Release
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UPDATE_WAITS_FOR_WIFI
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.canInstall
import com.tmplayer.data.downloadAndInstall
import com.tmplayer.data.release
import com.tmplayer.data.unknownSourcesIntent
import com.tmplayer.data.updateScheduler
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.launch

/**
 * The update popup: "TMPlayer 1.20.0 is out", what is new, how it installs, and *Update now*,
 * *Remind me later* and *Skip this version*. Then the download, and then Android takes over.
 *
 * The side bar item, the popup that opens by itself once per version and Settings all show this
 * same dialog, because [Updates] holds the state and this is only a window onto it. It also answers
 * Settings' "Check for updates" while the check runs and when there turns out to be nothing new.
 * It closes itself once the installer is on screen, since what happens next is the system's
 * business.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsStateWithLifecycle()
    val settings = remember { SettingsStore(context) }
    val scheduler = remember { updateScheduler(context) }
    val wifiOnly by settings.wifiOnlyDownloads.collectAsStateWithLifecycle(initialValue = false)
    val metered by NetworkMonitor.metered.collectAsStateWithLifecycle()
    val confirm = remember { FocusRequester() }
    val touch = isTouch()

    // The device has to be told, once, that installs from TMPlayer are allowed. Android will not
    // take that answer from in here, so the viewer is sent to the switch and comes back with Back.
    val allowed = Updates.canInstall(context)
    val release = state.release
    val offer = release?.let {
        Offer(
            release = it,
            skipped = (state as? UpdateState.Available)?.skipped == true,
            sizeBytes = Updates.apkFor(it)?.size ?: 0L,
            allowed = allowed,
            waitsForWifi = metered && wifiOnly,
            onMobileData = metered && !wifiOnly,
        )
    }

    val primary: () -> Unit = {
        when {
            offer != null && !allowed -> runCatching {
                context.startActivity(Updates.unknownSourcesIntent(context))
            }
            // Outlives the dialog: Back on a remote closes it, and the download carries on, with
            // the rail item there to reopen it.
            offer != null -> Background.scope.launch { Updates.downloadAndInstall(context, offer.release) }
            else -> scope.launch { scheduler.checkNow() }
        }
    }
    // On the process's scope rather than this dialog's: closing the dialog would cancel the write.
    val later: () -> Unit = {
        Background.scope.launch { scheduler.remindLater() }
        onDismiss()
    }
    val skip: () -> Unit = {
        release?.let { Background.scope.launch { scheduler.skip(it.version) } }
        onDismiss()
    }

    // The release page is where a failed install is finished by hand, and where the whole of the
    // notes live. A phone opens it in the browser; a TV, which has none, shows it as a QR code.
    var qrUrl by remember { mutableStateOf<String?>(null) }
    val releasePage: () -> Unit = {
        val url = releasePageUrl(release)
        if (!openLink(context, url)) qrUrl = url
    }
    // Offered where the popup has nothing else to do: after a failure, and when there is no update.
    val showReleasePage = state is UpdateState.Failed || state is UpdateState.Idle
    qrUrl?.let { ReleasePageQrDialog(it, onClose = { qrUrl = null }) }

    // Handing over to the installer is the end of this dialog's job.
    LaunchedEffect(state) {
        if (state is UpdateState.Ready) onDismiss()
    }

    if (touch) {
        TouchUpdateDialog(state, offer, primary, later, skip, onDismiss, releasePage.takeIf { showReleasePage })
        return
    }

    FloatingWindow(onDismiss = onDismiss, ignoreRelease = true) {
        // A ceiling rather than a width: 620dp is a comfortable paragraph on a television.
        val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)

        Column(
            Modifier
                .width(panel)
                .floatingSurface()
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
                    title(state, offer),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Tone.text,
                )
            }

            body(state, offer, device = "TV").forEach { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state is UpdateState.Failed) Tone.danger else Tone.muted,
                )
            }

            (state as? UpdateState.Downloading)?.let { downloading ->
                Spacer(Modifier.height(6.dp))
                ProgressBar(downloading.fraction)
            }

            Spacer(Modifier.height(10.dp))
            // A remote steps along a row; nothing here while the download runs, so a stray
            // press cannot abandon it. Four buttons are wider than the panel, so the row wraps.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state is UpdateState.Downloading -> Unit
                    offer != null -> {
                        TmButton(
                            onClick = primary,
                            enabled = offer.allowed.not() || !offer.waitsForWifi,
                            modifier = Modifier.focusRequester(confirm).paneAction(),
                        ) { Text(primaryLabel(state, offer)) }
                        if (showReleasePage) {
                            TmSecondaryButton(onClick = releasePage, modifier = Modifier.paneAction()) {
                                Text(RELEASE_PAGE)
                            }
                        }
                        TmSecondaryButton(onClick = later, modifier = Modifier.paneAction()) {
                            Text(UpdateWords.LATER)
                        }
                        TmSecondaryButton(onClick = skip, modifier = Modifier.paneAction()) {
                            Text(UpdateWords.SKIP)
                        }
                    }
                    // The button names the check while it runs, not just the body: the button
                    // is what the remote is pointed at, so a slow answer must show there.
                    else -> {
                        TmButton(
                            onClick = primary,
                            loading = state is UpdateState.Checking,
                            busyLabel = s.updateChecking,
                            modifier = Modifier.focusRequester(confirm).paneAction(),
                        ) { Text(s.updateCheckAgain) }
                        if (showReleasePage) {
                            TmSecondaryButton(onClick = releasePage, modifier = Modifier.paneAction()) {
                                Text(RELEASE_PAGE)
                            }
                        }
                        TmSecondaryButton(onClick = onDismiss, modifier = Modifier.paneAction()) {
                            Text(s.commonClose)
                        }
                    }
                }
            }
        }
    }

    // A remote needs a starting point, and Update now is the one the popup is for.
    LaunchedEffect(offer != null) { runCatching { confirm.requestFocus() } }
}

/** A release on offer, with what this device makes of it. */
private class Offer(
    val release: Release,
    val skipped: Boolean,
    val sizeBytes: Long,
    val allowed: Boolean,
    val waitsForWifi: Boolean,
    val onMobileData: Boolean,
)

/** The button that opens the release page, or on a TV shows it as a QR code. */
private val RELEASE_PAGE: String get() = L.updateReleasePage

/** As wide as this dialog ever gets, on any screen. */
private val PANEL_MAX = 620.dp

/**
 * The phone's version: Material's own dialog rather than the hand-built panel above, which is drawn
 * for a television and its D-pad. On a phone the platform already decides the scrim, the width, the
 * corner radius, the button order, tap-outside to dismiss and the screen reader announcement.
 */
@Composable
private fun TouchUpdateDialog(
    state: UpdateState,
    offer: Offer?,
    onPrimary: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onReleasePage: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val downloading = state as? UpdateState.Downloading
    TmAlertDialog(
        // A download in progress is the one state that must not be dismissed by a stray tap
        // outside it: the dialog is what is holding the download's own progress on screen.
        onDismissRequest = { if (downloading == null) onDismiss() },
        // Amber on a white card is barely a colour, so Tone hands the phone a darker one.
        icon = { M3Icon(Icons.Filled.Refresh, contentDescription = null, tint = Tone.caution) },
        title = { M3Text(title(state, offer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body(state, offer, device = "phone").forEach { paragraph ->
                    M3Text(paragraph, color = if (state is UpdateState.Failed) Tone.danger else Color.Unspecified)
                }
                if (downloading != null) {
                    // An unknown length becomes the indeterminate bar rather than an empty trough.
                    val fraction = downloading.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                // Under the words rather than in the button row, which is already three wide.
                if (onReleasePage != null) {
                    TextButton(onClick = onReleasePage) { M3Text(s.updateOpenReleasePage) }
                }
            }
        },
        confirmButton = {
            if (downloading != null) return@TmAlertDialog
            // A dialog's button row is too narrow for a spinner beside the words, so the label
            // names the check while it runs, and the button is disabled against a second press.
            val checking = state is UpdateState.Checking
            Button(
                onClick = onPrimary,
                enabled = !checking && (offer == null || !offer.allowed || !offer.waitsForWifi),
            ) {
                M3Text(
                    when {
                        checking -> s.updateChecking
                        offer != null -> primaryLabel(state, offer)
                        else -> s.updateCheckAgain
                    },
                )
            }
        },
        dismissButton = {
            if (downloading != null) return@TmAlertDialog
            Row {
                if (offer != null) {
                    TextButton(onClick = onSkip) { M3Text(UpdateWords.SKIP) }
                    TextButton(onClick = onLater) { M3Text(UpdateWords.LATER) }
                } else {
                    TextButton(onClick = onDismiss) { M3Text(s.commonClose) }
                }
            }
        },
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
            .clip(CircleShape)
            .background(Tone.muted.copy(alpha = 0.25f)),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Caution),
            )
        }
    }
}

private fun title(state: UpdateState, offer: Offer?): String = when {
    offer != null -> UpdateWords.title(offer.release, offer.skipped)
    state is UpdateState.Idle -> L.updateUpToDate
    else -> L.updateCheckingForUpdates
}

private fun primaryLabel(state: UpdateState, offer: Offer): String = when {
    !offer.allowed -> L.updateOpenSetting
    state is UpdateState.Failed -> UpdateWords.TRY_AGAIN
    offer.onMobileData && offer.sizeBytes > 0 ->
        L.updateNowOnMobileData(action = UpdateWords.UPDATE_NOW, size = Translator.messages.formatter.bytes(offer.sizeBytes))
    else -> UpdateWords.UPDATE_NOW
}

/**
 * The paragraphs under the title. A failure replaces them all; otherwise the installed version and
 * the release's notes, then one line on how the update reaches this device.
 *
 * @param device what to call the machine this is running on, which the install notice names.
 */
private fun body(state: UpdateState, offer: Offer?, device: String): List<String> {
    if (state is UpdateState.Failed) return listOf(state.message)
    if (offer == null) {
        return listOf(
            if (state is UpdateState.Checking) {
                L.updateAskingGithub
            } else {
                L.updateNewest(version = Updates.installedVersion, page = Updates.RELEASES_PAGE)
            },
        )
    }
    val size = Translator.messages.formatter.bytes(offer.sizeBytes)
    val how = when {
        state is UpdateState.Downloading -> L.updateDownloading(size = size)
        !offer.allowed && device == "tv" ->
            L.updateBlockedTv
        !offer.allowed ->
            L.updateBlockedPhone
        offer.waitsForWifi -> UPDATE_WAITS_FOR_WIFI
        else -> L.updateSizeAndConfirm(size = size)
    }
    return listOf(
        listOf(UpdateWords.youHave(Updates.installedVersion), offer.release.notes)
            .filter { it.isNotBlank() }
            .joinToString(" "),
        how,
    )
}
