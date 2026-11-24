package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SignOutGate
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Where a sign out is: nothing, the first question, or past it (the backup, the failure, the sign out itself). */
enum class SignOutPhase { Idle, Asking, Running }

/** The prompt the host shows now. */
internal enum class SignOutPrompt { None, Ask, Saving, BackupFailed }

/**
 * Everything a sign out holds between the press and the end (WP-02): the prompt, the two ticks, the
 * backup job, the downloads' size. It lives at the app shell, above Browse and Settings, so the
 * TV's account menu, the phone's popover and Settings all send the same [request], and a backup
 * started from a menu finishes after that menu has closed: nothing here belongs to a screen that
 * can be left. Make it with [rememberSignOutState], draw it with [SignOutHost].
 */
@Stable
class SignOutState internal constructor(asking: Boolean = false) {
    private var prompt by mutableStateOf(if (asking) SignOutPrompt.Ask else SignOutPrompt.None)
    internal var failedReason by mutableStateOf("")

    /** The sign out dialog's tick box. Off every time it opens: downloads are the viewer's files. */
    internal var alsoDeleteDownloads by mutableStateOf(false)

    /** "Save a backup before you sign out" (FQ12): on every time the dialog opens, one tick to turn it off. */
    internal var backupFirst by mutableStateOf(true)

    /** The app lock's PIN was given for this question (when Settings' own gate is not open). */
    internal var pinOk by mutableStateOf(false)

    /** The downloads' size, worked out off the main thread when the question opens. */
    internal var downloadBytes by mutableLongStateOf(0L)

    /** The backup and the sign out itself, on the host's own scope so a closing menu cannot cancel them. */
    internal var job: Job? = null

    /** The sign out is under way after the first question ("Signing out…"). */
    var signingOut by mutableStateOf(false)
        internal set

    internal val asking: Boolean get() = prompt == SignOutPrompt.Ask

    internal fun show(next: SignOutPrompt) {
        prompt = next
    }

    internal fun promptNow(): SignOutPrompt = prompt

    val phase: SignOutPhase
        get() = when {
            prompt == SignOutPrompt.Ask -> SignOutPhase.Asking
            prompt != SignOutPrompt.None || signingOut -> SignOutPhase.Running
            else -> SignOutPhase.Idle
        }

    /**
     * Asks "Sign out of Telegram as <name>?". A second request while a prompt is up, or a sign out
     * is running, is ignored: the same press twice never stacks two questions or two sign outs.
     */
    fun request(): Boolean {
        if (phase != SignOutPhase.Idle) return false
        alsoDeleteDownloads = false
        backupFirst = true
        pinOk = false
        prompt = SignOutPrompt.Ask
        return true
    }

    /**
     * The app locked while a question stood (the app lock closing on leaving the app): the question
     * and the PIN given for it go, so after unlocking it asks the PIN again. A backup that is already
     * running stays: nothing of it is drawn while locked, and it is not cut.
     */
    internal fun appLocked() {
        pinOk = false
        if (prompt == SignOutPrompt.Ask) {
            prompt = SignOutPrompt.None
            alsoDeleteDownloads = false
            backupFirst = true
        }
    }

    internal fun reset() {
        pinOk = false
        prompt = SignOutPrompt.None
        alsoDeleteDownloads = false
        backupFirst = true
    }

    internal companion object {
        /** Only the first question survives a rotation: nothing has run yet, and a lost later prompt is harmless. */
        val Saver: Saver<SignOutState, Boolean> = Saver(
            save = { it.asking },
            restore = { SignOutState(asking = it) },
        )
    }
}

@Composable
fun rememberSignOutState(): SignOutState = rememberSaveable(saver = SignOutState.Saver) { SignOutState() }

/**
 * The sign out prompts and the work behind them, composed once at the shell (WP-02). Texts are
 * Settings' own `settings.sign_out_*`, with the backup tick, for every profile count; the title
 * names the account when one is known ([name]). The sign out itself goes through
 * [com.tmplayer.ui.lock.ProfileFlow.signOutOpen], which signs out the open profile only.
 *
 * @param onLoggedOut the shell moves on once the profile is signed out.
 */
@Composable
fun SignOutHost(
    state: SignOutState,
    name: String?,
    settings: SettingsStore,
    watchedStore: WatchedStore,
    onLoggedOut: () -> Unit,
    /**
     * Whether Settings' own gate is open (the app lock's PIN given this visit, or none set). Signing out
     * is Settings' press: with a PIN set and the gate shut (from the bar's menu, the phone's popover) the
     * PIN is asked first, and a wrong PIN or Cancel signs nothing out.
     */
    settingsUnlocked: Boolean = true,
    /** More than one profile on this device; null reads it from the profile list. */
    severalProfiles: Boolean? = null,
    /** The app lock is shut ("ask when the app opens"): no prompt is drawn over it, and a question waiting is dropped. */
    appLocked: Boolean = false,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val named = name?.takeIf { it.isNotBlank() }
    val lock by settings.appLock.collectAsState(initial = null)
    // A lock not yet read decides nothing: no question and no pad until the store has answered.
    val gate = SignOutGate.of(lock, settingsUnlocked, state.pinOk)
    LaunchedEffect(appLocked) { if (appLocked) state.appLocked() }
    val profileFlow = remember { com.tmplayer.ui.profiles.AndroidProfiles.flow(context) }
    val profileList by remember(profileFlow) {
        profileFlow?.switcher?.profiles ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsState()
    val several = severalProfiles ?: (profileList?.let { com.tmplayer.data.ProfilePicker.atLaunch(it) } == true)
    val title = if (named != null) s.settingsSignOutTitleNamed(named) else s.settingsSignOutTitle

    // The size of the downloads, off the main thread, when the question opens.
    LaunchedEffect(state.asking) {
        if (state.asking) {
            state.downloadBytes = withContext(Dispatchers.IO) {
                runCatching { StorageSplit.measure(context).downloadBytes }.getOrDefault(0L)
            }
        }
    }

    // A backup that cannot be saved is shown with the way out (BackupFailed) and never keeps the
    // viewer from signing out.
    fun signOutNow(withBackup: Boolean) {
        // A second press while the first is under way (two quick OKs) starts nothing more.
        if (state.job?.isActive == true) return
        val deleteDownloads = state.alsoDeleteDownloads
        // The prompt changes before anything launches, so the next press finds another prompt.
        if (withBackup) {
            // A prompt, not a spinner: Sign out anyway stays in reach while the backup is sent.
            state.show(SignOutPrompt.Saving)
        } else {
            state.reset()
            state.signingOut = true
        }
        // On the app's scope, not the composition's: the host leaves the screen with the gates
        // (the app lock, a profile switch) and the job must neither be cut nor leave "Saving" up.
        state.job = com.tmplayer.platform.Background.scope.launch {
            if (withBackup) {
                val service = com.tmplayer.data.BackupService(
                    settings = settings,
                    watched = watchedStore,
                    metadata = com.tmplayer.online.OnlineMetadata.current?.store,
                    appVersion = com.tmplayer.BuildConfig.VERSION_NAME,
                    bookmarks = com.tmplayer.data.BookmarkStore(context),
                )
                val result = com.tmplayer.data.SignOutBackup.save({ service.export() }, service.fileName(), settings = settings)
                // Cancelled (Cancel, or Sign out anyway): what the answer was no longer matters.
                kotlin.coroutines.coroutineContext.ensureActive()
                if (result is com.tmplayer.data.BackupSend.Failed) {
                    state.failedReason = result.reason
                    state.show(SignOutPrompt.BackupFailed)
                    return@launch
                }
                state.reset()
                state.signingOut = true
            }
            // The downloads stay unless the box was ticked (forgetProfile says the rest). Through
            // the profiles (HX88): only this profile goes, then the picker, the one left, or the
            // sign in when it was the last.
            val flow = com.tmplayer.ui.profiles.AndroidProfiles.flow(context)
            if (flow != null) {
                // Signed out: the shell moves on. Not (cancelled while it waited, refused): the
                // page stays as it was, its "Signing out" line cleared.
                flow.signOutOpen(deleteDownloads) { result ->
                    state.signingOut = false
                    if (result is com.tmplayer.data.SignOutResult.SignedOut) onLoggedOut()
                }
            } else {
                forgetAccount(context, settings, watchedStore, deleteDownloads)
                Td.logOut()
                state.signingOut = false
                onLoggedOut()
            }
        }
    }

    // Over a locked app nothing is drawn, whatever is pending.
    if (appLocked) return

    when (state.promptNow()) {
        SignOutPrompt.None -> Unit

        // The PIN first, in a window of its own over whatever asked (the TV's menu stays under it): a wrong
        // PIN or Cancel closes the question and signs nothing out.
        SignOutPrompt.Ask -> if (gate == SignOutGate.Wait) {
            Unit
        } else if (gate == SignOutGate.Pin) {
            FloatingWindow(onDismiss = { state.reset() }) {
                PinPad(
                    settings = settings,
                    purpose = PinPurpose.Check,
                    title = s.lockEnterForSignOut,
                    onDone = { state.pinOk = true },
                    onCancel = { state.reset() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else TvConfirm(
            title = title,
            // With other profiles on the device the words say they stay (the web's accounts.sign_out_message).
            message = if (several && named != null) s.profilesSignOutBody(named) else s.settingsSignOutMessage,
            detail = if (state.downloadBytes > 0) s.settingsSignOutDownloadsStay else null,
            confirmLabel = s.settingsSignOutConfirm,
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // On by default (FQ12): a backup of the settings, places and bookmarks in the
                    // viewer's own Saved Messages, sent before anything is cleared.
                    TickRow(
                        label = s.signoutBackupTick,
                        checked = state.backupFirst,
                        onToggle = { state.backupFirst = !state.backupFirst },
                    )
                    if (state.downloadBytes > 0) {
                        TickRow(
                            label = s.settingsSignOutDeleteDownloads(s.formatter.bytes(state.downloadBytes)),
                            checked = state.alsoDeleteDownloads,
                            onToggle = { state.alsoDeleteDownloads = !state.alsoDeleteDownloads },
                        )
                    }
                }
            },
            onConfirm = { signOutNow(withBackup = state.backupFirst) },
            onDismiss = { state.reset() },
        )

        SignOutPrompt.Saving -> TvConfirm(
            title = title,
            message = s.signoutBackupSaving,
            confirmLabel = s.signoutBackupAnyway,
            onConfirm = {
                state.job?.cancel()
                signOutNow(withBackup = false)
            },
            onDismiss = {
                state.job?.cancel()
                state.reset()
            },
        )

        // The backup could not be saved: the reason, and the way out. Back returns to the first
        // dialog (where Sign out tries again, or the tick can be turned off); Sign out anyway goes on.
        SignOutPrompt.BackupFailed -> TvConfirm(
            title = s.signoutBackupFailedTitle,
            message = s.signoutBackupFailedMessage(state.failedReason),
            detail = s.signoutBackupFailedDetail,
            confirmLabel = s.signoutBackupAnyway,
            cancelLabel = s.commonBack,
            onConfirm = { signOutNow(withBackup = false) },
            onDismiss = { state.show(SignOutPrompt.Ask) },
        )
    }
}
