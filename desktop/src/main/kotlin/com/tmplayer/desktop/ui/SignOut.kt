package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.tmplayer.data.BackupSend
import com.tmplayer.data.BackupService
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SignOutBackup
import com.tmplayer.online.OnlineMetadata
import kotlinx.coroutines.Job
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.BookmarkStore
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the sign out dialog is: asking, saving the backup to Saved Messages first, or that having failed. */
private sealed interface SignOutPhase {
    data object Asking : SignOutPhase
    data object Saving : SignOutPhase
    data class Failed(val reason: String) : SignOutPhase
}

/**
 * Signing out (B6, Decision E5). Downloads are files in the viewer's own folder now, not cache, so
 * they stay unless the box is ticked; favourites, history, the watched list and the cache go as before.
 *
 * "Save a backup before you sign out" (FQ12): one more box, ticked, that sends the settings backup to
 * the viewer's own Saved Messages before anything is cleared. While it saves the dialog says so and
 * keeps "Sign out anyway" in reach; a failure says why and offers the same, or Back to try again.
 * It never keeps the viewer from signing out.
 */
@Composable
internal fun SignOutDialog(state: ShellState, onDismiss: () -> Unit, name: String? = null) {
    val s = LocalStrings.current
    val settings = state.settings
    // Signing out is Settings' press: asked from the big screen's menu with a PIN set and Settings shut
    // (WP-02), the app lock's PIN comes first, and a wrong PIN or Cancel signs nothing out.
    val lock by settings.appLock.collectAsState(initial = null)
    var pinGiven by remember { mutableStateOf(false) }
    val named = name?.takeIf { it.isNotBlank() }
    val title = if (named != null) s.settingsSignOutTitleNamed(named) else s.signoutTitle
    var alsoDownloads by remember { mutableStateOf(false) }
    var backupFirst by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf<SignOutPhase>(SignOutPhase.Asking) }
    var saving by remember { mutableStateOf<Job?>(null) }
    var size by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        size = withContext(Dispatchers.IO) { DownloadIndex.bytesOnDisk(runCatching { settings.downloadsNow() }.getOrDefault(emptyList())) }
    }
    // The profile the question named, when it was answered (SEC-04): a switch landing while the
    // backup is sent never has another profile signed out under this one's question.
    var asked by remember { mutableStateOf<String?>(null) }
    fun answered() {
        asked = com.tmplayer.data.AccountSwitcher.current()?.profiles?.value?.activeId
    }
    fun signOutNow() {
        onDismiss()
        // One sign out at a time for both hosts (the menu's, Settings' page): a second confirm while
        // the first is queued signs nothing out again (SEC-08).
        if (state.signingOut) return
        val deleteDownloads = alsoDownloads
        // On the process's scope, not this dialog's: closing the dialog cancelled its scope
        // part way, every step's runCatching swallowed the cancel, nothing was cleared and the
        // logout itself was never sent, so the window sat on Connecting for good.
        // Through the profiles (HX88): only this profile goes ([desktopForgetProfile] says what),
        // then the picker, the one left, or the sign in when it was the last. The flow runs on the
        // app's scope: a switch builds this window again.
        val flow = com.tmplayer.desktop.profiles.DesktopProfiles.flow()
        state.signingOut = true
        if (flow != null) {
            flow.signOutOpen(deleteDownloads, expected = asked) { result ->
                java.awt.EventQueue.invokeLater {
                    state.signingOut = false
                    if (result is com.tmplayer.data.SignOutResult.SignedOut) {
                        state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                    }
                }
            }
            return
        }
        com.tmplayer.platform.Background.scope.launch {
            try {
                signOut(settings, deleteDownloads, state.watched, state.bookmarks)
                withContext(Dispatchers.Main) {
                    state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                }
                Td.logOut()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { state.signingOut = false }
            }
        }

    }
    fun saveThenSignOut() {
        // A second press while the backup is sent starts nothing more.
        if (phase == SignOutPhase.Saving) return
        phase = SignOutPhase.Saving
        // On the process's scope, so the popover or the page closing never cancels the backup half way.
        saving = com.tmplayer.platform.Background.scope.launch {
            val service = BackupService(settings, state.watched, OnlineMetadata.current?.store, com.tmplayer.desktop.BuildInfo.VERSION, bookmarks = state.bookmarks)
            val result = SignOutBackup.save({ service.export() }, service.fileName(), settings = settings)
            ensureActive()
            if (result is BackupSend.Failed) phase = SignOutPhase.Failed(result.reason) else signOutNow()
        }
    }
    // A sign out already under way (asked from the other host): no second question (SEC-08).
    if (state.signingOut && phase == SignOutPhase.Asking) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    // A lock not yet read decides nothing: no question and no pad until the store has answered.
    val gate = com.tmplayer.data.SignOutGate.of(lock, state.settingsUnlocked, pinGiven)
    if (gate == com.tmplayer.data.SignOutGate.Wait) return
    if (gate == com.tmplayer.data.SignOutGate.Pin) {
        AccountPinDialog(state, onDone = { pinGiven = true }, onCancel = onDismiss)
        return
    }
    when (val shown = phase) {
        SignOutPhase.Asking -> ConfirmDialog(
            title = title,
            message = s.signoutBody(DesktopPaths.profileDownloadsDir.path),
            confirmLabel = s.signoutConfirm,
            onDismiss = onDismiss,
            onConfirm = {
                answered()
                if (backupFirst) saveThenSignOut() else signOutNow()
            },
            extra = {
                Column {
                    CheckRow(backupFirst, { backupFirst = it }, s.signoutBackupTick)
                    CheckRow(
                        alsoDownloads,
                        { alsoDownloads = it },
                        if (size > 0) s.commonWithDetail(s.signoutDeleteDownloads, StorageRelocationPlan.size(size)) else s.signoutDeleteDownloads,
                    )
                }
            },
        )
        SignOutPhase.Saving -> ConfirmDialog(
            title = title,
            message = s.signoutBackupSaving,
            confirmLabel = s.signoutBackupAnyway,
            onDismiss = {
                saving?.cancel()
                onDismiss()
            },
            onConfirm = {
                saving?.cancel()
                signOutNow()
            },
        )
        is SignOutPhase.Failed -> ConfirmDialog(
            title = s.signoutBackupFailedTitle,
            message = s.signoutBackupFailedMessage(shown.reason),
            detail = s.signoutBackupFailedDetail,
            cancelLabel = s.commonBack,
            confirmLabel = s.signoutBackupAnyway,
            onDismiss = { phase = SignOutPhase.Asking },
            onConfirm = { signOutNow() },
        )
    }
}

/**
 * Everything this app knows about the account that is leaving goes, except the downloads that
 * have a file of their own, which stay in the index unless [deleteDownloads].
 */
internal suspend fun signOut(
    settings: SettingsStore,
    deleteDownloads: Boolean,
    watched: WatchedStore? = null,
    bookmarks: BookmarkStore? = null,
) {
    val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    if (deleteDownloads) downloads.forEach { runCatching { DownloadIndex.delete(settings, it) } }
    // TDLib's logOut destroys its own files as well, so a large cache is not waited on for long.
    runCatching { withTimeoutOrNull(CLEAR_CACHE_MS) { Td.clearMediaCache() } }
    // Consent was the leaving account's to give: reporting stops, and a crash waiting to be asked
    // about goes, as on the phone. clearEverything turns the switch itself off.
    runCatching { com.tmplayer.desktop.DesktopCrashReports.stop() }
    runCatching { com.tmplayer.desktop.DesktopCrashReports.discardPending() }
    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
    // What this account watched is its own as much as the preferences are, and it lives in a
    // file of its own, so it is cleared on its own, and so is Watch later, which shares it.
    watched?.let {
        runCatching { it.clear() }
        runCatching { it.watchLater.clear() }
    }
    // The bookmarks go with it, for the same reason.
    bookmarks?.let { runCatching { it.clear() } }
    runCatching { com.tmplayer.data.SavedMessagesBackup.forgetFiles() }
    // The online subtitles picked for this account's videos, and their kept copies (HX25).
    runCatching { com.tmplayer.online.OnlineSubtitles.current?.picks?.clear() }
    com.tmplayer.online.SubtitlePicks.wipe(java.io.File(com.tmplayer.desktop.DesktopPaths.dataDir, "online-subtitle-picks.json"))
}

/** How long signing out waits for the video cache to be cleared before logging out regardless. */
private const val CLEAR_CACHE_MS = 5_000L

/** The app lock's PIN in a window of its own, before a sign out asked from outside Settings. */
@Composable
private fun AccountPinDialog(state: ShellState, onDone: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .width(380.dp)
                .height(600.dp)
                .background(com.tmplayer.ui.theme.Tone.background),
        ) {
            com.tmplayer.ui.lock.PinPad(
                settings = state.settings,
                purpose = com.tmplayer.ui.lock.PinPurpose.Check,
                title = LocalStrings.current.lockEnterForSignOut,
                onDone = { onDone() },
                onCancel = onCancel,
            )
        }
    }
}
