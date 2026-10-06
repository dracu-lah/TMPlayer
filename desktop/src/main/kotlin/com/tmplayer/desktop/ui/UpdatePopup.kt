package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Release
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateWords
import com.tmplayer.data.Updates
import com.tmplayer.data.release
import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.SelfUpdate
import com.tmplayer.desktop.UpdateProgress
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.platform.Background
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the side bar and the rail show for a newer version: "Update" with the version in the badge
 * ("Update 1.20.0" read together), or "Restart to update" once it is installed and waiting.
 */
data class NavUpdate(val label: String, val version: String)

/**
 * The side bar item for the current update state: a release is out and was not skipped, or one has
 * been installed and wants a restart. A version skipped and then re-offered by Settings stays out
 * of the side bar; Settings and the popup say it.
 */
@Composable
fun rememberNavUpdate(state: ShellState): NavUpdate? {
    val s = LocalStrings.current
    val update by Updates.state.collectAsState()
    val progress = state.extras.selfUpdate?.progress?.collectAsState()?.value
    if (progress is UpdateProgress.Ready) return NavUpdate(s.updateRestartToUpdate, progress.version)
    val release = update.release ?: return null
    if ((update as? UpdateState.Available)?.skipped == true) return null
    return NavUpdate(s.updateNavItem, release.version)
}

/**
 * Opens the popup by itself, once per version, [UpdateScheduler.POPUP_DELAY_MS] after the side bar
 * item has appeared, so the viewer sees where it lives. Never over a playing video: the wait
 * starts again when the player closes. Only composed inside the signed in shell, so never over the
 * sign in screen either.
 */
@Composable
fun UpdatePopupTrigger(state: ShellState, item: NavUpdate?) {
    val scheduler = state.extras.updates ?: return
    val playing = state.nowPlaying != null
    val version = item?.version?.takeIf { Updates.state.value.release?.version == it }
    LaunchedEffect(version, playing) {
        if (version == null || playing) return@LaunchedEffect
        delay(UpdateScheduler.POPUP_DELAY_MS)
        if (scheduler.shouldPopUp(version)) {
            scheduler.popupShown(version)
            state.updatePopup = true
        }
    }
}

/** The popup, wired to the live update state. Closed by setting [ShellState.updatePopup] to false. */
@Composable
fun UpdatePopupHost(state: ShellState) {
    if (!state.updatePopup || state.nowPlaying != null) return
    val update by Updates.state.collectAsState()
    val selfUpdate = state.extras.selfUpdate
    val progress = selfUpdate?.progress?.collectAsState()?.value ?: UpdateProgress.Idle
    val release = update.release
    val close = { state.updatePopup = false }
    if (release == null) {
        // Skipped from elsewhere, or installed: nothing left to say.
        LaunchedEffect(Unit) { close() }
        return
    }
    UpdatePopup(
        release = release,
        skipped = (update as? UpdateState.Available)?.skipped == true,
        installed = BuildInfo.VERSION,
        kind = selfUpdate?.kind ?: InstallKind.Manual,
        canUpdate = selfUpdate?.canUpdateTo(release) == true,
        progress = progress,
        // Outlives the popup, which can be hidden while the download runs.
        onUpdate = { Background.scope.launch { selfUpdate?.update(release) } },
        onOpenPage = {
            OpenExternal.browse(release.pageUrl)
            close()
        },
        onLater = {
            selfUpdate?.reset()
            // On the process's scope: closing the popup would cancel this one's.
            Background.scope.launch { state.extras.updates?.remindLater() }
            close()
        },
        onSkip = {
            selfUpdate?.reset()
            Background.scope.launch { state.extras.updates?.skip(release.version) }
            close()
        },
        onRestart = { selfUpdate?.restart() },
        onClose = close,
    )
}

/**
 * "TMPlayer 1.20.0 is out": what is new, how this install takes it, and the three choices. The
 * download, the check, the install and Restart now all happen in here, where the corner notice
 * used to show them.
 */
@Composable
fun UpdatePopup(
    release: Release,
    skipped: Boolean,
    installed: String,
    kind: InstallKind,
    canUpdate: Boolean,
    progress: UpdateProgress,
    onUpdate: () -> Unit,
    onOpenPage: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val busy = progress is UpdateProgress.Downloading || progress == UpdateProgress.Verifying || progress == UpdateProgress.Installing
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = Tone.caution) },
        title = { Text(UpdateWords.title(release, skipped)) },
        text = {
            Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    listOf(UpdateWords.youHave(installed), release.notes).filter { it.isNotBlank() }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    updateLine(kind, canUpdate, progress, release),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (progress is UpdateProgress.Failed) Tone.danger else Tone.muted,
                )
                when (progress) {
                    is UpdateProgress.Downloading -> {
                        val fraction = progress.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                        }
                    }
                    UpdateProgress.Verifying, UpdateProgress.Installing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when {
                progress is UpdateProgress.Ready -> Button(onClick = onRestart) { Text(s.updateRestartNow) }
                busy -> Unit
                !canUpdate -> Button(onClick = onOpenPage) { Text(UpdateWords.OPEN_RELEASE_PAGE) }
                else -> Button(onClick = onUpdate) {
                    Text(if (progress is UpdateProgress.Failed) UpdateWords.TRY_AGAIN else UpdateWords.UPDATE_NOW)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    // Later keeps the side bar item, which now reads "Restart to update".
                    progress is UpdateProgress.Ready -> TextButton(onClick = onClose) { Text(s.updateLaterShort) }
                    // The download goes on with the popup closed; the item reopens it.
                    busy -> TextButton(onClick = onClose) { Text(s.commonHide) }
                    else -> {
                        TextButton(onClick = onSkip) { Text(UpdateWords.SKIP) }
                        TextButton(onClick = onLater) { Text(UpdateWords.LATER) }
                    }
                }
            }
        },
    )
}

/** The line under the notes: how this install takes the update, or how far it has got. */
internal fun updateLine(kind: InstallKind, canUpdate: Boolean, progress: UpdateProgress, release: Release): String =
    when (progress) {
        is UpdateProgress.Downloading -> {
            val size = SelfUpdate.assetFor(kind)?.let(release.assets::get)?.size ?: 0L
            val percent = progress.fraction?.let { L.messages.formatter.percent(it.toDouble()) }
            when {
                size > 0 && percent != null -> L.updateDownloadingSizePercent(StreamStats.formatBytes(size), percent)
                size > 0 -> L.updateDownloadingSize(StreamStats.formatBytes(size))
                percent != null -> L.updateDownloadingPercent(percent)
                else -> L.updateDownloadingPlain
            }
        }
        UpdateProgress.Verifying -> L.updateVerifying
        UpdateProgress.Installing -> L.updateInstalling
        is UpdateProgress.Ready -> L.updateReady
        is UpdateProgress.Failed -> progress.message
        UpdateProgress.Idle -> SelfUpdate.retiredLine(kind, release) ?: when {
            canUpdate && (kind == InstallKind.Deb || kind == InstallKind.Rpm) ->
                L.updateHowPackage
            canUpdate -> L.updateHowSelf
            kind == InstallKind.Flatpak -> L.updateHowFlatpak
            else -> L.updateHowManual
        }
    }
