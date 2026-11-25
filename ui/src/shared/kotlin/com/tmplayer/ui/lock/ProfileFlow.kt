package com.tmplayer.ui.lock

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AccountEvent
import com.tmplayer.data.AccountList
import com.tmplayer.data.AccountSwitcher
import com.tmplayer.data.AddResult
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.PickerModel
import com.tmplayer.data.Profile
import com.tmplayer.data.ProfilePicker
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.data.ProfileState
import com.tmplayer.data.ProfileTile
import com.tmplayer.data.RemoveResult
import com.tmplayer.data.SignOutResult
import com.tmplayer.data.SwitchResult
import com.tmplayer.data.SwitchState
import com.tmplayer.data.TileStatus
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * What each platform gives the profile screens (HX88): whether something of the open profile is
 * running, how to stop it before a switch, and its own sign out. Android implements it in :app;
 * the desktop and iOS do the same in phase B2.
 */
interface ProfilePlatform {
    /** A video of the open profile plays now (the player, picture in picture, a cast). */
    fun playing(): Boolean

    /** A download of the open profile is moving or queued. */
    fun downloading(): Boolean

    /**
     * Saves the player's place and stops it, and pauses the open profile's downloads, while its
     * stores are still the open ones. [com.tmplayer.data.BeforeSwitch.runAll] plus the platform's
     * own, all together under one deadline; [onWaiting] hears what is still being waited for.
     */
    suspend fun beforeSwitch(onWaiting: (List<String>) -> Unit = {})

    /**
     * The viewer cancelled a switch while it waited ([ProfileFlow.cancelWaiting]): the profile
     * stays, so what [beforeSwitch] paused goes on again (the downloads it paused).
     */
    suspend fun switchCancelled() = Unit

    /**
     * The platform's sign out of [profile], run by [AccountSwitcher.signOutProfile] with it open:
     * its stores ([com.tmplayer.data.SettingsStore.clearAccountData], or `clearEverything` when
     * [last]), the watched and bookmark stores, the downloads when [deleteDownloads].
     */
    suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean)

    /** A short message (a toast, a snackbar). */
    fun say(text: String)

    /**
     * The device's app lock (CP57): the picker asks its PIN before signing a profile out, removing
     * one or adding one (as Settings does before signing out), and before the picker itself when
     * it asks when the app opens. Null emits for a platform without one.
     */
    val appLock: Flow<AppLockSettings?> get() = flowOf(null)

    /**
     * The app lock as it is now, read at once when the flow is made; null when it could not be
     * read yet (everything then asks the app lock's PIN until [appLock] says, fail closed). A
     * platform without an app lock answers [AppLockSettings] with nothing set.
     */
    fun appLockNow(): AppLockSettings? = AppLockSettings()

    /** The app lock's PIN, for that check; null when there is none to ask. */
    fun appLockChecker(): PinChecker? = null
}

/**
 * The profile screens' state for the process (HX88): which panel is up, the line while a switch
 * runs, and whether the viewer has picked a profile since the process started. Held by the
 * platform for as long as the process lives, so a rotation or a recreated window finds it as it
 * was, and a process that died starts at the picker again (a PIN is asked again then).
 *
 * Every switch, add and sign out runs on [scope] (the app's, never a screen's) and in
 * [AccountSwitcher] one at a time: pressing a tile twice, or two tiles fast, opens the last asked.
 */
class ProfileFlow(
    val switcher: AccountSwitcher,
    private val platform: ProfilePlatform,
    private val scope: CoroutineScope,
) {
    /** What is drawn over the picker, or over the shell for [notice]. */
    sealed interface Stage {
        data object Picker : Stage
        data class Pin(val id: String, val why: PinWhy) : Stage
        data class ChoosePin(val id: String) : Stage
        data class Options(val id: String) : Stage
        data class Rename(val id: String) : Stage

        /** A video plays or a download runs: asked first. [id] null is Add account. */
        data class ConfirmSwitch(val id: String?, val playing: Boolean) : Stage
        data class ConfirmSignOut(val id: String) : Stage
        data class Recoverable(val id: String, val reason: ProfileState.Reason) : Stage

        /** The app lock's PIN before [then], which acts on [id] (null for Add account). */
        data class AppPin(val id: String?, val then: Guarded) : Stage
    }

    /** What the app lock's PIN guards in the picker: what Settings would ask it for. */
    enum class Guarded { SignOut, Remove, Add, RemoveAll }

    /** What a profile's PIN is asked for. */
    enum class PinWhy { Open, Rename, ChangePin, RemovePin, SignOut, Remove, RemoveAll }

    /**
     * A message over whatever is on screen. With a [retry] it is a failure that can be asked for
     * again: Try again runs it, Send logs sits beside it, OK leaves it.
     */
    data class Notice(val title: String, val body: String, val retry: (() -> Unit)? = null)

    var stage: Stage by mutableStateOf(Stage.Picker)
        private set

    var notice: Notice? by mutableStateOf(null)
        private set

    /** "Opening Asha", or the slow close's line, while a switch, an add or a sign out runs. */
    var status: String? by mutableStateOf(null)
        private set

    /** The viewer has picked (or added) a profile since the process started: the shell shows. */
    var entered: Boolean by mutableStateOf(false)
        private set

    /** The picker was asked for from the profile menu, or a sign out left two profiles or more. */
    var asked: Boolean by mutableStateOf(false)
        private set

    /** The tile the focus goes back to once a panel closes, asked again on each [focusAsk]. */
    var focusOn: String? by mutableStateOf(null)
        private set
    var focusAsk: Int by mutableIntStateOf(0)
        private set

    private var running: Job? = null
    private var listening = false

    /**
     * Add account's work runs (from the moment it is asked, before its turn comes): with the shell
     * shown, [ProfilesGate] draws its line over it, so a press is never answered by nothing.
     */
    var adding: Boolean by mutableStateOf(false)
        private set

    /** The work now ending was stopped by the viewer's Cancel, not cut short by anything else. */
    @kotlin.concurrent.Volatile
    private var viewerCancelled = false

    /** The switch is waiting for work of the open profile to stop: Cancel stays on it. */
    var waitCancellable: Boolean by mutableStateOf(false)
        private set

    /** Cancel while a switch waits ([waitCancellable]): nothing is switched, the open profile stays. */
    fun cancelWaiting() {
        // Exactly one wins: a Cancel that comes after the wait is over does nothing, so the switch
        // never goes ahead with the downloads it paused running again. The resume runs in the
        // cancelled work itself ([stopFirst]), so a retry waits for it.
        if (!wait.cancel()) return
        viewerCancelled = true
        running?.cancel()
        // The remote goes back where it was before the line came up.
        focusAsk++
    }

    private val wait = com.tmplayer.data.SwitchWait()

    /** A switch or sign out that did not happen: what its wait paused goes on again. */
    private suspend fun resumePaused() {
        // Inside the work, waited for: the next work (a retry) starts only after it, so its wait
        // never records a set that this resume then takes away.
        withContext(NonCancellable) { runCatching { platform.switchCancelled() } }
    }

    /** The open profile's work stops first, the line saying what it waits for, with a Cancel. */
    private suspend fun stopFirst() {
        val line = status
        wait.begin()
        waitCancellable = true
        try {
            platform.beforeSwitch { what -> status = if (what.isEmpty()) line else L.profilesWaiting(what = what.joinToString(", ")) }
        } catch (e: CancellationException) {
            // The viewer's Cancel: the profile stays, what the wait paused goes on again.
            if (!wait.waiting) resumePaused()
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "stopping before a switch failed", e)
        } finally {
            waitCancellable = false
            status = line
        }
        // A Cancel that won: the switch does not go on, even if it was not cancelled in time to stop the wait.
        if (!wait.commit()) {
            viewerCancelled = true
            resumePaused()
            throw CancellationException("the viewer cancelled the switch")
        }
    }

    /**
     * The app lock as last read, null until it is. A flow rather than snapshot state: the flow is
     * made inside the first composition (a `remember` in the shell), and the store answers on the
     * app's scope before that composition is applied on a slow TV. A snapshot state written then was
     * put back to unread when the composition applied, so Add account waited on a lock it never knew
     * and drew nothing for the life of the process (Mi TV, 2026-11-25). Screens collect [appLock].
     */
    private val lockRead = MutableStateFlow<AppLockSettings?>(null)

    /** The app lock as read from the store, null until the first answer; for screens to collect. */
    val appLock: StateFlow<AppLockSettings?> get() = lockRead

    /** The app lock as last read; meaningful once [appLockKnown]. */
    val appLockSettings: AppLockSettings? get() = lockRead.value

    /** Whether the app lock was read at all: until it is, everything it guards asks (fail closed). */
    val appLockKnown: Boolean get() = lockRead.value != null

    init {
        // Heard from the start, with or without a screen: switches and the app lock lock it.
        ProfileUnlock.install()
        // The app lock from the store, now and as it changes, on the app's scope: no screen sets it.
        runCatching { platform.appLockNow() }.getOrNull()?.let { lockRead.value = it }
        scope.launch {
            runCatching {
                platform.appLock.collect {
                    lockRead.value = it ?: AppLockSettings()
                    afterLockRead()
                }
            }.onFailure { if (it is CancellationException) throw it else Logger.w(TAG, "the app lock could not be read", it) }
        }
        // The app lock closing, or the app leaving the screen whatever the lock's options: a PIN given
        // before it counts for nothing after it (SEC-01), and nothing asked or answered behind a PIN
        // is left for whoever comes next (SEC-02).
        AppLockSession.onLock { forgetAnswers(appLocked = true) }
        com.tmplayer.data.SettingsUnlock.onAppLeft { forgetAnswers(appLocked = false) }
    }

    /**
     * What a PIN was given for goes: the profile PIN given at the picker, the sign out it cleared.
     * When the app lock closed ([appLocked]), the notice (its Try again and Send logs) and any panel
     * past a PIN go too, so the one who unlocks the app finds the picker, not someone else's answers.
     */
    private fun forgetAnswers(appLocked: Boolean) {
        pinGiven = null
        signOutCleared = null
        if (!appLocked) return
        notice = null
        removeAllAsk = null
        manage = false
        if (stage != Stage.Picker) stage = Stage.Picker
    }

    /**
     * Add account pressed before the app lock was read waits on its pad, drawn nothing until the
     * lock is known (fail closed). Read with no PIN after all, it goes on at once rather than
     * leaving a pad that asks for a PIN nobody set; read with one, the pad shows now.
     */
    private fun afterLockRead() {
        val now = stage
        if (now is Stage.AppPin && now.then == Guarded.Add && now.id == null && !appLockPin) {
            stage = Stage.Picker
            addChecked()
        }
    }

    /** The profile whose PIN was just given at the picker, until its switch has opened it. */
    private var pinGiven: String? = null

    /** The profile whose PIN the sign out gate took (or which needs none), the only one [confirmSignOut] acts on. */
    private var signOutCleared: String? = null

    /** Remove all accounts waiting on its PINs: the profiles still to ask, and what to run after. */
    private class RemoveAllAsk(val deleteDownloads: Boolean, val done: (SignOutResult) -> Unit, val left: ArrayDeque<String>)

    private var removeAllAsk: RemoveAllAsk? = null

    /** Manage profiles is on: a press on a tile opens its options. */
    var manage: Boolean by mutableStateOf(false)

    /** The app lock has a PIN, or was not read yet: the picker's sign out, remove and add ask it first. */
    val appLockPin: Boolean get() = !appLockKnown || appLockSettings?.pinSet == true

    fun appLockChecker(): PinChecker? = platform.appLockChecker()

    /**
     * The open profile has a PIN that was not given in this process ([ProfileUnlock]): nothing of
     * it is drawn until it is, with one profile or several, after a launch, a switch, a sign out, a
     * remove, an add or a long absence.
     */
    fun needsUnlock(list: AccountList): Boolean = ProfileUnlock.needsUnlock(list)

    /** The open profile's PIN was given at the gate's pad. */
    fun unlockOpen(id: String) {
        if (switcher.profiles.value.activeId == id) ProfileUnlock.unlock(id)
    }

    private fun hasPin(id: String): Boolean = switcher.profiles.value.byId(id)?.pin != null

    /** The picker's model now, for [ProfilePicker.model]. */
    fun model(list: AccountList, activeNeedsSignIn: Boolean): PickerModel =
        ProfilePicker.model(list, switcher::stateOf, activeNeedsSignIn)

    private fun tile(id: String, activeNeedsSignIn: Boolean = false): ProfileTile? =
        model(switcher.profiles.value, activeNeedsSignIn).tiles.firstOrNull { it.id == id }

    /** The name a tile goes by, for the panels' words. */
    fun nameOf(id: String?): String = id?.let { tile(it) }?.let(ProfileWords::name) ?: L.profilesAdd

    /** Hears the switcher's events ([AccountEvent.SameAccount]) for the process; once. */
    fun listen() {
        if (listening) return
        listening = true
        scope.launch {
            switcher.events.collect { event ->
                when (event) {
                    is AccountEvent.SameAccount -> notice = Notice(
                        L.profilesSameAccountTitle,
                        L.profilesSameAccountBody(name = event.existing.shownName.ifBlank { L.profilesUnnamed(number = 1) }),
                    )
                }
            }
        }
    }

    /** The profile menu's Switch profile: the picker over the open profile. */
    fun ask() {
        asked = true
        stage = Stage.Picker
        focusOn = switcher.profiles.value.activeId
        focusAsk++
    }

    /** Back from a picker the menu opened: the open profile again, as it was. */
    fun close() {
        pinGiven = null
        asked = false
        stage = Stage.Picker
        if (switcher.profiles.value.profiles.isNotEmpty()) entered = true
    }

    /** Try again on a failure notice: it closes, and what failed is asked for again the way it was first. */
    fun retryNotice() {
        val again = notice?.retry
        notice = null
        again?.invoke()
    }

    fun dismissNotice() {
        notice = null
        // The remote goes back to the tile it came from, never into the void the panel left.
        focusAsk++
    }

    /** Back to the row, the focus on [id]'s tile. */
    fun back(id: String? = null) {
        // A panel given up (Cancel on "stop the video first?"): the PIN given for it is spent.
        pinGiven = null
        stage = Stage.Picker
        focusOn = id
        focusAsk++
    }

    private fun enter() {
        stage = Stage.Picker
        manage = false
        asked = false
        entered = true
    }

    // ---- presses on the row ----------------------------------------------------------------

    /** A press on [tile]: its PIN first, or what is broken about it, then the switch. */
    fun open(tile: ProfileTile) {
        if (status != null) return
        when {
            tile.status == TileStatus.NeedsAttention -> {
                val state = switcher.profiles.value.byId(tile.id)?.let(switcher::stateOf)
                val reason = (state as? ProfileState.Recoverable)?.reason ?: ProfileState.Reason.FolderMissing
                stage = Stage.Recoverable(tile.id, reason)
            }
            // Every time: a profile left and opened again from the picker asks again.
            ProfilePicker.needsPin(tile) -> stage = Stage.Pin(tile.id, PinWhy.Open)
            else -> afterPin(tile.id)
        }
    }

    /** The profile is unlocked (or has no PIN): open it now, or ask first when something runs. */
    private fun afterPin(id: String) {
        val list = switcher.profiles.value
        if (list.activeId == id && list.switchingTo == null) {
            if (pinGiven == id) ProfileUnlock.unlock(id)
            pinGiven = null
            enter()
            return
        }
        val playing = runCatching { platform.playing() }.getOrDefault(false)
        val downloading = runCatching { platform.downloading() }.getOrDefault(false)
        if (ProfilePicker.asksFirst(playing, downloading)) {
            stage = Stage.ConfirmSwitch(id, playing)
        } else {
            switchNow(id, signInAgain = false)
        }
    }

    /** Add account: a fresh profile on the normal sign in, after asking when something runs. */
    fun add() {
        // Already under way: its line is up (over the shell, or on the picker), nothing to add twice.
        if (adding) return
        // Another switch or sign out runs: said, never a press that does nothing.
        if (status != null) {
            notice = Notice(L.profilesAdd, L.profilesAddBusy, retry = ::addAfterWork)
            return
        }
        if (appLockPin) {
            stage = Stage.AppPin(null, Guarded.Add)
            return
        }
        addChecked()
    }

    private fun addChecked() {
        val playing = runCatching { platform.playing() }.getOrDefault(false)
        val downloading = runCatching { platform.downloading() }.getOrDefault(false)
        if (ProfilePicker.asksFirst(playing, downloading)) stage = Stage.ConfirmSwitch(null, playing) else addNow()
    }

    /** The viewer agreed to stop what runs. */
    fun confirmSwitch() {
        when (val now = stage) {
            is Stage.ConfirmSwitch -> if (now.id == null) addNow() else switchNow(now.id, signInAgain = false)
            else -> Unit
        }
    }

    fun options(tile: ProfileTile) {
        if (status != null) return
        stage = Stage.Options(tile.id)
    }

    // ---- options -------------------------------------------------------------------------------

    /** Rename, Set or Change and Remove the PIN ask a locked profile's PIN first; Sign out never does. */
    fun rename(id: String) {
        stage = if (tile(id)?.locked == true) Stage.Pin(id, PinWhy.Rename) else Stage.Rename(id)
    }

    fun setPin(id: String) {
        stage = if (tile(id)?.locked == true) Stage.Pin(id, PinWhy.ChangePin) else Stage.ChoosePin(id)
    }

    fun removePin(id: String) {
        stage = Stage.Pin(id, PinWhy.RemovePin)
    }

    /**
     * Sign out of [id]: a profile with a PIN that is not the open, unlocked one asks its PIN (a
     * locked profile is never signed out by someone who has not unlocked it), then the app lock's.
     */
    fun signOut(id: String) {
        signOutCleared = null
        stage = when {
            hasPin(id) && !ProfileUnlock.isUnlocked(id) -> Stage.Pin(id, PinWhy.SignOut)
            appLockPin -> Stage.AppPin(id, Guarded.SignOut)
            else -> {
                signOutCleared = id
                Stage.ConfirmSignOut(id)
            }
        }
    }

    /** The app lock's PIN was given: what it guarded goes on. */
    fun appPinAccepted(id: String?, then: Guarded) {
        when (then) {
            Guarded.SignOut -> {
                signOutCleared = id
                stage = if (id != null) Stage.ConfirmSignOut(id) else Stage.Picker
            }
            Guarded.Remove -> if (id == null) back() else if (needsOwnPinToRemove(id)) stage = Stage.Pin(id, PinWhy.Remove) else removeNow(id)
            Guarded.Add -> addChecked()
            Guarded.RemoveAll -> nextRemoveAllPin()
        }
    }

    /** The PIN pad accepted the PIN for [why]. */
    fun pinAccepted(id: String, why: PinWhy) {
        when (why) {
            PinWhy.Open -> {
                pinGiven = id
                afterPin(id)
            }
            PinWhy.SignOut -> {
                if (appLockPin) {
                    stage = Stage.AppPin(id, Guarded.SignOut)
                } else {
                    signOutCleared = id
                    stage = Stage.ConfirmSignOut(id)
                }
            }
            PinWhy.Remove -> removeNow(id)
            PinWhy.RemoveAll -> {
                removeAllAsk?.left?.remove(id)
                nextRemoveAllPin()
            }
            PinWhy.Rename -> stage = Stage.Rename(id)
            PinWhy.ChangePin -> stage = Stage.ChoosePin(id)
            PinWhy.RemovePin -> launch(id) {
                if (switcher.setPin(id, null)) platform.say(L.lockPinRemovedToast)
                back(id)
            }
        }
    }

    fun pinChosen(id: String, pin: String) {
        launch(id) {
            if (switcher.setPin(id, pin)) {
                // Whoever chose it knows it: the open profile does not lock them out at once.
                if (switcher.profiles.value.activeId == id) ProfileUnlock.unlock(id)
                platform.say(L.lockPinSetToast)
            }
            back(id)
        }
    }

    fun saveName(id: String, name: String) {
        launch(id) {
            switcher.rename(id, name)
            back(id)
        }
    }

    // ---- the work, on the app's scope ------------------------------------------------------

    private fun launch(id: String? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a profile action failed", e)
                status = null
                // A sentence, never the raw text: it can name folders or TDLib's insides (SEC-11).
                notice = Notice(L.profilesSwitchFailed(name = nameOf(id)), com.tmplayer.data.Failures.humanise(e.message))
            }
        }
    }

    /** One switch at a time from here too; the switcher itself keeps only the newest asked. */
    private fun work(
        line: String,
        failure: () -> Notice,
        onFailed: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        val before = running
        running = scope.launch {
            try {
                before?.join()
            } catch (e: CancellationException) {
                onEnd?.invoke()
                throw e
            }
            viewerCancelled = false
            status = line
            val watch = scope.launch {
                // Only the slow close's line: the line itself was set above, and a late Idle
                // must never put it back after the work ended (it left the row disabled).
                switcher.state.collect { state ->
                    if (state is SwitchState.ClosingSlowly) status = L.profilesClosingSlowly
                }
            }
            try {
                block()
            } catch (e: CancellationException) {
                // Not the viewer's Cancel (a timeout inside it, say) while the app goes on: said
                // like any failure, never a line that just goes away with nothing done.
                if (!viewerCancelled && scope.isActive) {
                    Logger.w(TAG, "a profile action was cut short", e)
                    notice = failure()
                    resumePaused()
                }
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a profile switch failed", e)
                // Whom it was for is named: " could not be opened" with no name was no answer.
                notice = failure()
                // Nothing happened as asked: the open profile's paused work goes on again.
                resumePaused()
                onFailed?.invoke()
            } finally {
                withContext(NonCancellable) { watch.cancelAndJoin() }
                status = null
                onEnd?.invoke()
            }
        }
    }

    /** A switch that did not open [name]: Try again asks for the same switch, behind the same PIN rules. */
    private fun switchFailed(id: String, name: String, signInAgain: Boolean) =
        Notice(L.profilesSwitchFailed(name = name), "", retry = { retrySwitch(id, signInAgain) })

    private fun signOutFailed(name: String, title: String = L.profilesSignOut) = Notice(title, L.profilesSignOutFailed(name = name))

    /**
     * Try again on a failed switch. A profile with a PIN asks it again, as a press on its tile does:
     * the notice can stay up for hours, and the PIN given for the switch that failed was spent on it
     * (SEC-01).
     */
    private fun retrySwitch(id: String, signInAgain: Boolean) {
        if (signInAgain) {
            switchNow(id, signInAgain = true)
            return
        }
        val tile = tile(id)
        if (tile != null && ProfilePicker.needsPin(tile)) open(tile) else switchNow(id, signInAgain = false)
    }

    private fun switchNow(id: String, signInAgain: Boolean) {
        // The PIN given at the picker counts for this switch alone, taken now: a switch that fails,
        // is superseded or lands on a broken profile leaves nothing behind for a later one (SEC-01).
        val given = pinGiven == id
        pinGiven = null
        val name = nameOf(id)
        stage = Stage.Picker
        focusOn = id
        work(L.profilesSwitching(name = name), failure = { switchFailed(id, name, signInAgain) }) {
            val left = switcher.profiles.value.active
            stopFirst()
            // Whatever opens next is locked until its own PIN is given (also heard from the switch itself).
            ProfileUnlock.lock()
            when (val result = switcher.switchTo(id, signInAgain)) {
                SwitchResult.Switched, SwitchResult.Already -> {
                    // Its PIN was given at the picker for this very switch: open, and unlocked.
                    if (given) ProfileUnlock.unlock(id)
                    // Leaving a profile that never finished signing in is Add account given up:
                    // it goes, folders and all, so a half made profile never lingers as a tile.
                    if (left != null && left.id != id && left.adding && !left.signedIn) switcher.remove(left.id)
                    enter()
                }
                // A newer switch took over: it is that one's to finish or resume.
                SwitchResult.Superseded -> Unit
                is SwitchResult.Recoverable -> {
                    stage = Stage.Recoverable(id, result.state.reason)
                    resumePaused()
                }
                else -> {
                    notice = switchFailed(id, name, signInAgain)
                    // Nothing switched: what the wait paused goes on again.
                    resumePaused()
                }
            }
        }
    }

    /** Add account that did not happen: Try again asks for it again, behind the app lock's PIN again. */
    private fun addFailed() = Notice(L.profilesAdd, L.profilesAddFailed, retry = ::addAfterWork)

    /**
     * Try again on an Add account notice: the notice is up a moment before the work that showed it
     * has ended, and a press in that moment must not find it still running and do nothing.
     */
    private fun addAfterWork() {
        val last = running
        if (last == null || last.isCompleted) add() else scope.launch { last.join(); add() }
    }

    private fun addNow() {
        stage = Stage.Picker
        adding = true
        work(L.profilesAddingNow, failure = ::addFailed, onEnd = { adding = false }) {
            stopFirst()
            ProfileUnlock.lock()
            val result = switcher.add()
            when (result) {
                is AddResult.Added -> enter()
                AddResult.Full -> notice = Notice(L.profilesAdd, L.profilesLimit(count = com.tmplayer.data.AccountRules.MAX))
                is AddResult.NoSpace -> notice = Notice(
                    L.profilesNoSpaceTitle,
                    L.profilesNoSpaceBody(needed = L.messages.formatter.size(result.neededBytes), free = L.messages.formatter.size(result.freeBytes)),
                    retry = ::addAfterWork,
                )
                else -> notice = addFailed()
            }
            // Nothing added, the open profile stays: what the wait paused goes on again.
            if (result !is AddResult.Added) resumePaused()
        }
    }

    /** Sign out of [id] only: its data goes, then the picker (two left or more), the one left, or the sign in. */
    fun confirmSignOut(id: String, deleteDownloads: Boolean) {
        // Only past the gate in signOut: no caller skips a locked profile's PIN or the app lock's.
        if (signOutCleared != id) {
            signOut(id)
            return
        }
        signOutCleared = null
        val name = nameOf(id)
        stage = Stage.Picker
        work(L.profilesSwitching(name = name), failure = { signOutFailed(name) }) {
            // Always: the open profile's own long jobs stop and are waited for before its data goes.
            stopFirst()
            ProfileUnlock.lock()
            when (val result = switcher.signOutProfile(id) { profile, last -> platform.wipe(profile, last, deleteDownloads) }) {
                is SignOutResult.SignedOut -> {
                    platform.say(if (result.partialWipe) L.profilesWipePartial else L.profilesSignedOut(name = name))
                    afterSignOut(result)
                }
                else -> {
                    notice = Notice(L.profilesSignOut, L.profilesSignOutFailed(name = name))
                    // Nothing was signed out: what the wait paused goes on again.
                    resumePaused()
                }
            }
        }
    }

    /** Where a sign out lands: the picker while two profiles or more are left, else straight in. */
    private fun afterSignOut(result: SignOutResult.SignedOut) {
        val next = result.next
        if (next != null && ProfilePicker.atLaunch(switcher.profiles.value)) {
            asked = true
            entered = false
            back(next.id)
        } else {
            enter()
        }
    }

    /**
     * Settings' sign out of the open profile, through the same switcher (its backup was asked before).
     * [expected] is the profile the question named, when it was answered: a switch that landed since
     * (or one under way) signs nobody out (SEC-04).
     */
    fun signOutOpen(deleteDownloads: Boolean, expected: String? = null, done: (SignOutResult) -> Unit = {}) {
        val list = switcher.profiles.value
        // Settings is only ever drawn for an unlocked profile; anything else is refused, and said (SEC-07).
        if (needsUnlock(list) || list.switchingTo != null || (expected != null && list.activeId != expected)) {
            notice = signOutFailed(nameOf(expected ?: list.activeId))
            done(SignOutResult.Unknown)
            return
        }
        confirmSignOutWith(list.activeId, deleteDownloads, done)
    }

    private fun confirmSignOutWith(id: String, deleteDownloads: Boolean, done: (SignOutResult) -> Unit) {
        val name = nameOf(id)
        work(L.profilesSwitching(name = name), failure = { signOutFailed(name) }, onFailed = { done(SignOutResult.Unknown) }) {
            try {
                // Settings' sign out too: the profile's long jobs stop and are waited for first.
                stopFirst()
            } catch (e: CancellationException) {
                // Cancelled while it waited: nothing was signed out, and Settings is told so.
                done(SignOutResult.Unknown)
                throw e
            }
            ProfileUnlock.lock()
            val result = switcher.signOutProfile(id) { profile, last -> platform.wipe(profile, last, deleteDownloads) }
            if (result is SignOutResult.SignedOut) {
                if (result.partialWipe) platform.say(L.profilesWipePartial)
                afterSignOut(result)
            } else {
                notice = Notice(L.profilesSignOut, L.profilesSignOutFailed(name = name))
                resumePaused()
            }
            done(result)
        }
    }

    /**
     * Settings' Remove all accounts: it wipes other people's profiles, so the app lock's PIN comes
     * first, then the PIN of every profile that has one, each in turn, all before anything is
     * wiped. A wrong PIN, a wait or Cancel at any of them wipes nothing.
     */
    fun removeAll(deleteDownloads: Boolean, done: (SignOutResult) -> Unit = {}) {
        if (status != null) return
        val locked = switcher.profiles.value.profiles.filter { it.pin != null }.map { it.id }
        removeAllAsk = RemoveAllAsk(deleteDownloads, done, ArrayDeque(locked))
        asked = true
        if (appLockPin) stage = Stage.AppPin(null, Guarded.RemoveAll) else nextRemoveAllPin()
    }

    private fun nextRemoveAllPin() {
        val ask = removeAllAsk ?: return back()
        val next = ask.left.firstOrNull()
        if (next != null) {
            stage = Stage.Pin(next, PinWhy.RemoveAll)
            return
        }
        removeAllAsk = null
        stage = Stage.Picker
        removeAllNow(ask.deleteDownloads, ask.done)
    }

    /** A PIN pad's Cancel: Remove all accounts given up wipes nothing and goes back where it came from. */
    fun cancelPin(id: String, why: PinWhy) {
        if (why == PinWhy.RemoveAll) cancelRemoveAll() else back(id)
    }

    fun cancelAppPin(id: String?, then: Guarded) {
        if (then == Guarded.RemoveAll) cancelRemoveAll() else back(id)
    }

    private fun cancelRemoveAll() {
        removeAllAsk = null
        close()
    }

    private fun removeAllNow(deleteDownloads: Boolean, done: (SignOutResult) -> Unit) {
        work(L.profilesRemoveAll, failure = { signOutFailed("", L.profilesRemoveAll) }, onFailed = { done(SignOutResult.Unknown) }) {
            stopFirst()
            ProfileUnlock.lock()
            val result = switcher.removeAll { profile, last -> platform.wipe(profile, last, deleteDownloads) }
            when {
                result is SignOutResult.SignedOut && result.partialWipe -> platform.say(L.profilesWipePartial)
                result !is SignOutResult.SignedOut -> {
                    notice = Notice(L.profilesRemoveAll, L.profilesSignOutFailed(name = ""))
                    resumePaused()
                }
            }
            if (result is SignOutResult.SignedOut) enter()
            done(result)
        }
    }

    /** A broken profile's Sign in again: its folders made afresh, then its sign in. */
    fun signInAgain(id: String) = switchNow(id, signInAgain = true)

    /** A broken (or never signed in) sleeping profile's Remove, without reaching Telegram. */
    fun remove(id: String) {
        stage = when {
            appLockPin -> Stage.AppPin(id, Guarded.Remove)
            needsOwnPinToRemove(id) -> Stage.Pin(id, PinWhy.Remove)
            else -> {
                removeNow(id)
                stage
            }
        }
    }

    /** A profile with a PIN whose folder is still there to read asks it before it goes. */
    private fun needsOwnPinToRemove(id: String): Boolean {
        val profile = switcher.profiles.value.byId(id) ?: return false
        if (profile.pin == null) return false
        val state = switcher.stateOf(profile)
        return !(state is ProfileState.Recoverable && state.reason != ProfileState.Reason.ReadOnly)
    }

    private fun removeNow(id: String) {
        // As every change of the list does ([ProfileUnlock]): the open profile asks its PIN again.
        ProfileUnlock.lock()
        launch(id) {
            when (switcher.remove(id)) {
                RemoveResult.Removed -> back()
                // The open one is signed out rather than removed.
                RemoveResult.Active, RemoveResult.Last -> stage = Stage.ConfirmSignOut(id)
                RemoveResult.Unknown -> back()
            }
        }
    }

    private companion object {
        const val TAG = "ProfileFlow"
    }
}

/**
 * The shell behind the profiles (HX88): the picker at launch with two profiles or more, the
 * picker again when the menu asks for it, and the shell itself keyed on the open profile, so every
 * screen's state is built again for the profile a switch opened. With one profile, or no [flow]
 * (a build that never opened the accounts), [content] is all there is and nothing changes.
 *
 * Back on the picker at launch is not taken: the platform's Back leaves the app from there. On a
 * picker the menu opened Back returns to the open profile. On a sign in screen with two profiles
 * or more ([signInStep]), Back brings the picker, so a profile whose login ended, or one being
 * added, is never a dead end.
 */
@Composable
fun ProfilesGate(
    flow: ProfileFlow?,
    signInStep: Boolean,
    sendLogs: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (flow == null) {
        content()
        return
    }
    LaunchedEffect(flow) { flow.listen() }
    val list by flow.switcher.profiles.collectAsState()
    val appUnlocked by AppLockSession.unlocked.collectAsState()
    // Read here only to draw: the locking itself happens in ProfileUnlock, with no screen needed,
    // and the app lock's state is the flow's, read from the store on the app's scope.
    val unlockedId by ProfileUnlock.unlockedId.collectAsState()
    val several = ProfilePicker.atLaunch(list)
    val show = several && (flow.asked || !flow.entered)
    // Collected, not read off the flow: its answer can land before this composition is applied.
    val lock = flow.appLock.collectAsState().value
    val lockKnown = lock != null
    // The app lock's pad is up (in [content] or here), or the lock was not read: fail closed.
    val appShut = lock?.let { it.pinSet && it.onOpen && !appUnlocked } ?: true
    Box(Modifier.fillMaxSize()) {
        when {
            // The app lock not read yet: nothing of the picker until it is (fail closed).
            !lockKnown && show -> Box(Modifier.fillMaxSize().background(Tone.background))
            // "Ask when the app opens": its PIN before the picker too, so the picker never shows
            // whose profiles these are, nor lets one be signed out, past the app's own lock.
            show && lock != null && lock.pinSet && lock.onOpen && !appUnlocked -> {
                val checker = remember(flow) { flow.appLockChecker() } ?: RefusingChecker
                PinPad(
                    checker = checker,
                    purpose = PinPurpose.Check,
                    title = L.lockEnterForApp,
                    onDone = { AppLockSession.unlock() },
                    onCancel = null,
                    brand = true,
                )
            }
            show -> ProfileFlowScreen(flow, list, signInStep)
            // The open profile's PIN, not given in this process: nothing of it shows until it is.
            // Back offers the other profiles when there are some; with one it leaves the app.
            ProfileUnlock.needsUnlock(list, unlockedId) -> key(list.activeId) {
                val id = list.activeId
                val checker = remember(id) { ProfilePinChecker(flow.switcher, id) }
                PinPad(
                    checker = checker,
                    purpose = PinPurpose.Check,
                    title = L.profilesPinFor(name = flow.nameOf(id)),
                    onDone = { flow.unlockOpen(id) },
                    onCancel = if (several) ({ flow.ask() }) else null,
                )
            }
            else -> {
                // The shell keeps the item the remote was on when a panel over it took the focus,
                // and gets it back when the panel is given up ([ProfileFlow.focusAsk]: OK on a
                // notice, Cancel on a PIN pad or a question, Cancel on Add account's wait).
                val shellFocus = remember { FocusRequester() }
                val overlayUp = flow.stage != ProfileFlow.Stage.Picker || flow.notice != null || flow.adding
                // Kept as the panel comes up, before it takes the remote a frame later.
                LaunchedEffect(overlayUp) { if (overlayUp) runCatching { shellFocus.saveFocusedChild() } }
                val seenAsk = remember { mutableIntStateOf(flow.focusAsk) }
                LaunchedEffect(flow.focusAsk) {
                    if (flow.focusAsk == seenAsk.intValue) return@LaunchedEffect
                    seenAsk.intValue = flow.focusAsk
                    // The panel's last frame first, and only once nothing else is over the shell.
                    withFrameNanos { }
                    snapshotFlow { flow.stage == ProfileFlow.Stage.Picker && flow.notice == null && !flow.adding }.first { it }
                    withFrameNanos { }
                    runCatching { if (!shellFocus.restoreFocusedChild()) shellFocus.requestFocus() }
                }
                Box(Modifier.fillMaxSize().focusRequester(shellFocus).focusGroup()) {
                    key(list.activeId) { content() }
                }
                // A profile on its sign in with others beside it: Back brings the picker.
                BackHandler(enabled = several && signInStep) { flow.ask() }
                // A panel a press in the shell asked for (Add account behind the app lock's PIN, or
                // asked first while a video plays), with one profile too: drawn over the shell,
                // which stays composed, so Cancel lands back where it came from (Settings).
                if (flow.stage != ProfileFlow.Stage.Picker && !appShut) ProfileFlowScreen(flow, list, signInStep)
                // Add account's work, pressed in the shell (the account menu, Settings): its line
                // over the shell, as the picker shows it on its row. Without it a one profile
                // install saw the menu close and nothing else while the open profile's work
                // stopped and the next client opened, and a second press was dropped unseen.
                if (flow.adding && flow.stage == ProfileFlow.Stage.Picker && !appShut) AddingPanel(flow)
                // Add account pressed before the app lock was read: no pad yet (fail closed, the
                // lock may have no PIN), but its wait with Cancel, never a press answered by nothing.
                // Nothing of a profile is on it; the shell under it is drawn already.
                val stage = flow.stage
                if (!lockKnown && stage is ProfileFlow.Stage.AppPin && stage.then == ProfileFlow.Guarded.Add && stage.id == null) {
                    AddingPanel(flow, onCancel = { flow.cancelAppPin(null, ProfileFlow.Guarded.Add) })
                }
            }
        }
        // Nothing of a profile's notice over the app lock's pad, nor before the lock is read: its Try
        // again, Send logs and the profile's name are past the app PIN (SEC-02). It shows once unlocked.
        if (!appShut) flow.notice?.let { notice ->
            ProfilePanel(
                title = notice.title,
                body = notice.body.ifBlank { null },
                // A failure that can be asked again leads with Try again (the remote starts there).
                actions = if (notice.retry != null) {
                    listOf(PanelAction(L.commonTryAgain, flow::retryNotice, primary = true), PanelAction(L.commonOk, flow::dismissNotice))
                } else {
                    listOf(PanelAction(L.commonOk, flow::dismissNotice, primary = true))
                },
                onDismiss = flow::dismissNotice,
                extra = if (notice.retry != null && sendLogs != null) ({ Box(Modifier.padding(top = 12.dp)) { sendLogs() } }) else null,
            )
        }
    }
}

/**
 * Whether the open profile's shell is what [ProfilesGate] draws now: not the picker, not a PIN.
 * For what sits over the gate (the toasts, "Wrong title?"): drawn only while this is true.
 */
@Composable
fun profileShellShown(flow: ProfileFlow?): Boolean {
    if (flow == null) return true
    val list by flow.switcher.profiles.collectAsState()
    val unlockedId by ProfileUnlock.unlockedId.collectAsState()
    val show = ProfilePicker.atLaunch(list) && (flow.asked || !flow.entered)
    return !show && !ProfileUnlock.needsUnlock(list, unlockedId)
}

/**
 * Add account under way, over the shell: a spinner and the line (what it waits for, the slow
 * close), with Cancel while the open profile's work is still being stopped. It holds the remote,
 * so nothing behind it is pressed while the profile changes under it.
 */
@Composable
private fun AddingPanel(flow: ProfileFlow, onCancel: (() -> Unit)? = null) {
    val line = flow.status ?: L.profilesAddingNow
    val cancel = onCancel ?: if (flow.waitCancellable) flow::cancelWaiting else null
    val actions = if (cancel != null) listOf(PanelAction(L.commonCancel, cancel)) else emptyList()
    // Back is Cancel while there is one; past that the switch is under way and Back waits with it.
    ProfilePanel(
        title = L.profilesAdd,
        body = line,
        actions = actions,
        onDismiss = { cancel?.invoke() },
        header = {
            CircularProgressIndicator(
                Modifier.padding(bottom = 16.dp).size(32.dp).clearAndSetSemantics { },
                color = Tone.accent,
                strokeWidth = 3.dp,
            )
        },
    )
}

/** A checker that takes no PIN: where one is required and none is there, nothing gets through. */
internal object RefusingChecker : PinChecker {
    override suspend fun waitUntil(): Long = 0L
    override suspend fun check(pin: String): com.tmplayer.data.PinCheck = com.tmplayer.data.PinCheck.Wrong(com.tmplayer.data.PinAttempts())
    override fun freeLeft(attempts: com.tmplayer.data.PinAttempts): Int = 0
}

/** The picker and whichever panel is over it. */
@Composable
fun ProfileFlowScreen(flow: ProfileFlow, list: AccountList, signInStep: Boolean) {
    val model = remember(list, signInStep) { flow.model(list, signInStep) }
    // From the menu, Back returns to the open profile; at launch it is left to the platform.
    if (flow.asked && flow.entered) BackHandler(enabled = flow.stage == ProfileFlow.Stage.Picker) { flow.close() }
    // Manage profiles is left first (composed later, so asked first): the button keeps the remote and reads Manage again.
    BackHandler(enabled = flow.manage && flow.stage == ProfileFlow.Stage.Picker) { flow.manage = false }
    // The row only while nothing is over it: a panel or a pad over a row the remote could still
    // reach let Down walk out of the panel onto a tile behind it. Closing one draws the row again
    // with the focus on the tile it came from ([ProfileFlow.focusOn]).
    if (flow.stage == ProfileFlow.Stage.Picker) {
        WhoIsWatching(
            model = model,
            onOpen = flow::open,
            onAdd = flow::add,
            onOptions = flow::options,
            onCancelStatus = if (flow.waitCancellable) flow::cancelWaiting else null,
            status = flow.status,
            focusOn = flow.focusOn,
            focusAsk = flow.focusAsk,
            manage = flow.manage,
            onManageChange = { flow.manage = it },
        )
    }
    val tile = { id: String -> model.tiles.firstOrNull { it.id == id } }
    when (val stage = flow.stage) {
        ProfileFlow.Stage.Picker -> Unit
        // Keyed on the stage: one profile's digits or wait never carry over to the next pad.
        is ProfileFlow.Stage.Pin -> key(stage) {
            val name = flow.nameOf(stage.id)
            val checker = remember(stage.id) { ProfilePinChecker(flow.switcher, stage.id) }
            PinPad(
                checker = checker,
                purpose = PinPurpose.Check,
                title = L.profilesPinFor(name = name),
                onDone = { flow.pinAccepted(stage.id, stage.why) },
                onCancel = { flow.cancelPin(stage.id, stage.why) },
            )
        }
        is ProfileFlow.Stage.ChoosePin -> PinPad(
            checker = null,
            purpose = PinPurpose.Choose,
            title = L.profilesPinNew(name = flow.nameOf(stage.id)),
            onDone = { pin -> flow.pinChosen(stage.id, pin) },
            onCancel = { flow.back(stage.id) },
        )
        is ProfileFlow.Stage.Options -> tile(stage.id)?.let { t ->
            ProfileOptions(
                tile = t,
                onRename = { flow.rename(t.id) },
                onSetPin = { flow.setPin(t.id) },
                onRemovePin = { flow.removePin(t.id) },
                onSignOut = { flow.signOut(t.id) },
                onSignInAgain = { flow.signInAgain(t.id) },
                onRemove = { flow.remove(t.id) },
                onDismiss = { flow.back(t.id) },
            )
        } ?: LaunchedEffect(stage) { flow.back() }
        is ProfileFlow.Stage.Rename -> RenameProfile(
            current = list.byId(stage.id)?.label.orEmpty(),
            onSave = { flow.saveName(stage.id, it) },
            onDismiss = { flow.back(stage.id) },
        )
        is ProfileFlow.Stage.ConfirmSwitch -> {
            val name = flow.nameOf(stage.id)
            ProfilePanel(
                title = if (stage.playing) L.profilesSwitchPlayTitle(name = name) else L.profilesSwitchDownloadsTitle(name = name),
                // The place is only spoken of when a video plays: downloads alone just pause.
                body = if (stage.playing) L.profilesSwitchPlayBody else L.profilesSwitchDownloadsBody,
                actions = listOf(
                    PanelAction(L.profilesSwitch, flow::confirmSwitch, primary = true),
                    PanelAction(L.commonCancel, { flow.back(stage.id) }),
                ),
                onDismiss = { flow.back(stage.id) },
            )
        }
        is ProfileFlow.Stage.ConfirmSignOut -> {
            val name = flow.nameOf(stage.id)
            var deleteDownloads by remember(stage.id) { mutableStateOf(false) }
            ProfilePanel(
                title = L.profilesSignOutTitle(name = name),
                body = L.profilesSignOutBody(name = name),
                actions = listOf(
                    PanelAction(L.signoutConfirm, { flow.confirmSignOut(stage.id, deleteDownloads) }, danger = true),
                    PanelAction(L.commonCancel, { flow.back(stage.id) }),
                ),
                onDismiss = { flow.back(stage.id) },
                extra = {
                    Row(
                        Modifier
                            .padding(top = 16.dp)
                            .toggleable(value = deleteDownloads, role = Role.Checkbox, onValueChange = { deleteDownloads = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteDownloads, onCheckedChange = null)
                        Text(L.signoutDeleteDownloads, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.padding(start = 8.dp))
                    }
                },
            )
        }
        is ProfileFlow.Stage.AppPin -> key(stage) {
            // No checker while the app lock says it has a PIN: refused, never let through.
            val checker = remember(flow) { flow.appLockChecker() } ?: RefusingChecker
            PinPad(
                checker = checker,
                purpose = PinPurpose.Check,
                title = L.lockEnterPin,
                onDone = { flow.appPinAccepted(stage.id, stage.then) },
                onCancel = { flow.cancelAppPin(stage.id, stage.then) },
            )
        }
        is ProfileFlow.Stage.Recoverable -> {
            val name = flow.nameOf(stage.id)
            ProfilePanel(
                title = L.profilesNeedsAttention,
                body = L.profilesRemoveBody(name = name, reason = ProfileWords.reason(stage.reason)),
                actions = listOf(
                    PanelAction(L.profilesSignInAgain, { flow.signInAgain(stage.id) }, primary = true),
                    PanelAction(L.profilesRemove, { flow.remove(stage.id) }, danger = true),
                    PanelAction(L.commonCancel, { flow.back(stage.id) }),
                ),
                onDismiss = { flow.back(stage.id) },
            )
        }
    }
}
