package com.tmplayer.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MeteredPolicy
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.FolderExport
import com.tmplayer.data.Release
import com.tmplayer.data.SelfInstaller
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UPDATE_WAITS_FOR_WIFI
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.canInstall
import com.tmplayer.data.downloadAndInstall
import com.tmplayer.data.points
import com.tmplayer.data.release
import com.tmplayer.data.unknownSourcesIntent
import com.tmplayer.data.workPausedByInstall
import com.tmplayer.data.updateScheduler
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.UpdateCard
import com.tmplayer.ui.components.UpdateCardProgress
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.launch

/**
 * The update popup: "TMPlayer 1.20.0 is out", what is new, how it installs, and *Update now*,
 * *Remind me later* and *Skip this version*. Then the download, and then the install: on Android
 * 12 and later in a session the popup stays up for, saying it is installing (and without Android's
 * question where it allows that, SelfInstaller); before that the system installer's screen takes
 * over.
 *
 * The side bar item, the popup that opens by itself once per version and Settings all show this
 * same dialog, because [Updates] holds the state and this is only a window onto it. It also answers
 * Settings' "Check for updates" while the check runs and when there turns out to be nothing new.
 * It closes itself once the system installer's screen is up, since what happens next is the
 * system's business.
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
    val storedWifiOnly by settings.wifiOnlyDownloads.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly = MeteredPolicy.wifiOnlyInForce(storedWifiOnly, FormFactor.isTv(context))
    val metered by NetworkMonitor.metered.collectAsStateWithLifecycle()
    val confirm = remember { FocusRequester() }
    val touch = isTouch()

    // The device has to be told, once, that installs from TMPlayer are allowed. Android will not
    // take that answer from in here, so the viewer is sent to the switch and comes back with Back.
    // Read again each time the app comes back to the front: nothing else redraws the dialog on
    // return, and a stale answer would send the viewer back to a switch already turned on.
    var allowed by remember { mutableStateOf(Updates.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Updates.canInstall(context)
        onPauseOrDispose { }
    }
    // Whether Update now installs without Android's question here, as far as the installed app
    // says; read again when the install permission may have changed.
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(allowed) { silent = runCatching { SelfInstaller.canUpdateSilently(context) }.getOrDefault(false) }
    // Downloads and a save to a folder pause for the install, which ends the process: said before
    // Update now is pressed. Collected so the line follows them.
    val downloads by OfflineDownloads.active.collectAsStateWithLifecycle()
    val export by FolderExport.state.collectAsStateWithLifecycle()
    val pausesWork = remember(downloads, export) { Updates.workPausedByInstall() }
    val release = state.release
    val offer = release?.let {
        Offer(
            release = it,
            skipped = (state as? UpdateState.Available)?.skipped == true,
            sizeBytes = Updates.apkFor(it)?.size ?: 0L,
            allowed = allowed,
            waitsForWifi = metered && wifiOnly,
            onMobileData = metered && !wifiOnly,
            silent = silent,
            pausesWork = pausesWork,
        )
    }

    val primary: () -> Unit = {
        when {
            // A second press while it runs does nothing: one download, one install.
            state.busy() -> Unit
            offer != null && !allowed -> runCatching {
                context.startActivity(Updates.unknownSourcesIntent(context))
            }
            // Outlives the dialog: Back on a remote closes it, and the download carries on, with
            // the rail item there to reopen it. The install after it too.
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

        UpdateCard(
            title = title(state, offer),
            installed = offer?.let { UpdateWords.youHave(Updates.installedVersion) },
            points = offer?.release?.points.orEmpty(),
            status = status(state, offer, device = "TV"),
            statusIsError = state is UpdateState.Failed,
            progress = cardProgress(state),
            tv = true,
            modifier = Modifier.width(panel).padding(vertical = 24.dp).floatingSurface(),
        ) {
            // A remote steps along a row; nothing here while the download or the install runs, so
            // a stray press cannot abandon it. Four buttons are wider than the panel, so the row
            // wraps.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.busy() -> Unit
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

    // A remote needs a starting point, and Update now is the one the popup is for: again once a
    // download or an install is over, since the buttons were not there while it ran (Android's
    // question answered with no puts the button back under the remote).
    val busy = state.busy()
    LaunchedEffect(offer != null, busy) {
        if (!busy) runCatching { confirm.requestFocus() }
    }
}

/** A release on offer, with what this device makes of it. */
private class Offer(
    val release: Release,
    val skipped: Boolean,
    val sizeBytes: Long,
    val allowed: Boolean,
    val waitsForWifi: Boolean,
    val onMobileData: Boolean,
    /** Android 12 and later will install it without asking. */
    val silent: Boolean,
    /** Downloads or a save to a folder are running, which the install pauses. */
    val pausesWork: Boolean,
)

/** A download or an install under way, when the popup offers no buttons. */
private fun UpdateState.busy(): Boolean = this is UpdateState.Downloading || this is UpdateState.Installing

/** The button that opens the release page, or on a TV shows it as a QR code. */
private val RELEASE_PAGE: String get() = L.updateReleasePage

/** As wide as this dialog ever gets, on any screen. */
private val PANEL_MAX = 620.dp

/**
 * The phone's version: the same card in a dialog of its own, with the buttons stacked for a thumb,
 * Update now across the full width and the two ways out under it. Tapping outside closes it, except
 * while the download runs, when the dialog is what holds the download's progress on screen.
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
    val downloading = state.busy()
    // A phone on its side has too little height to stack the list over the buttons.
    val short = LocalConfiguration.current.screenHeightDp < SHORT_SCREEN
    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UpdateCard(
            title = title(state, offer),
            installed = offer?.let { UpdateWords.youHave(Updates.installedVersion) },
            points = offer?.release?.points.orEmpty(),
            status = status(state, offer, device = "phone"),
            statusIsError = state is UpdateState.Failed,
            progress = cardProgress(state),
            split = short,
            modifier = Modifier
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = if (short) 12.dp else 24.dp)
                .widthIn(max = if (short) 860.dp else 560.dp)
                .floatingSurface(),
        ) {
            if (downloading) return@UpdateCard
            // A dialog's button is too narrow for a spinner beside the words, so the label names
            // the check while it runs, and the button is disabled against a second press.
            val checking = state is UpdateState.Checking
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onPrimary,
                    enabled = !checking && (offer == null || !offer.allowed || !offer.waitsForWifi),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    M3Text(
                        when {
                            checking -> s.updateChecking
                            offer != null -> primaryLabel(state, offer)
                            else -> s.updateCheckAgain
                        },
                    )
                }
                if (onReleasePage != null) {
                    TextButton(onClick = onReleasePage, modifier = Modifier.fillMaxWidth()) {
                        M3Text(s.updateOpenReleasePage)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    if (offer != null) {
                        TextButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                            M3Text(UpdateWords.SKIP, color = Tone.muted)
                        }
                        TextButton(onClick = onLater, modifier = Modifier.weight(1f)) {
                            M3Text(UpdateWords.LATER)
                        }
                    } else {
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            M3Text(s.commonClose)
                        }
                    }
                }
            }
        }
    }
}

/** Below this height (dp) the phone's card goes side by side. */
private const val SHORT_SCREEN = 520

private fun cardProgress(state: UpdateState): UpdateCardProgress = when (state) {
    is UpdateState.Downloading -> state.fraction?.let(UpdateCardProgress::Part) ?: UpdateCardProgress.Unknown
    is UpdateState.Installing -> UpdateCardProgress.Unknown
    else -> UpdateCardProgress.None
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
 * The line under What's new: a failure, the answer to a check, or how the update reaches this
 * device. The release's own notes are the card's list, not part of this.
 *
 * @param device what to call the machine this is running on, which the install notice names.
 */
private fun status(state: UpdateState, offer: Offer?, device: String): String {
    if (state is UpdateState.Failed) return state.message
    if (offer == null) {
        return if (state is UpdateState.Checking) {
            L.updateAskingGithub
        } else {
            L.updateNewest(version = Updates.installedVersion, page = Updates.RELEASES_PAGE)
        }
    }
    val size = Translator.messages.formatter.bytes(offer.sizeBytes)
    val line = when {
        state is UpdateState.Downloading -> L.updateDownloading(size = size)
        state is UpdateState.Installing -> return L.updateInstallingRestart
        !offer.allowed && device.equals("tv", ignoreCase = true) -> L.updateBlockedTv
        !offer.allowed -> L.updateBlockedPhone
        offer.waitsForWifi -> UPDATE_WAITS_FOR_WIFI
        offer.silent -> L.updateSizeAndRestart(size = size)
        else -> L.updateSizeAndConfirm(size = size)
    }
    return if (offer.pausesWork) "$line ${L.updatePausesDownloads}" else line
}
