package com.tmplayer.ui.lock

import androidx.compose.foundation.layout.Box
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
import com.tmplayer.data.PickerModel
import com.tmplayer.data.Profile
import com.tmplayer.data.ProfilePicker
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     * stores are still the open ones. [com.tmplayer.data.BeforeSwitch.runAll] plus the platform's own.
     */
    suspend fun beforeSwitch()

    /**
     * The platform's sign out of [profile], run by [AccountSwitcher.signOutProfile] with it open:
     * its stores ([com.tmplayer.data.SettingsStore.clearAccountData], or `clearEverything` when
     * [last]), the watched and bookmark stores, the downloads when [deleteDownloads].
     */
    suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean)

    /** A short message (a toast, a snackbar). */
    fun say(text: String)
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
    }

    /** What a profile's PIN is asked for. */
    enum class PinWhy { Open, Rename, ChangePin, RemovePin }

    /** A message with one way out, over whatever is on screen. */
    data class Notice(val title: String, val body: String)

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
        asked = false
        stage = Stage.Picker
        if (switcher.profiles.value.profiles.isNotEmpty()) entered = true
    }

    fun dismissNotice() {
        notice = null
    }

    /** Back to the row, the focus on [id]'s tile. */
    fun back(id: String? = null) {
        stage = Stage.Picker
        focusOn = id
        focusAsk++
    }

    private fun enter() {
        stage = Stage.Picker
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
            ProfilePicker.needsPin(tile) -> stage = Stage.Pin(tile.id, PinWhy.Open)
            else -> afterPin(tile.id)
        }
    }

    /** The profile is unlocked (or has no PIN): open it now, or ask first when something runs. */
    private fun afterPin(id: String) {
        val list = switcher.profiles.value
        if (list.activeId == id && list.switchingTo == null) {
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
        if (status != null) return
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

    fun signOut(id: String) {
        stage = Stage.ConfirmSignOut(id)
    }

    /** The PIN pad accepted the PIN for [why]. */
    fun pinAccepted(id: String, why: PinWhy) {
        when (why) {
            PinWhy.Open -> afterPin(id)
            PinWhy.Rename -> stage = Stage.Rename(id)
            PinWhy.ChangePin -> stage = Stage.ChoosePin(id)
            PinWhy.RemovePin -> launch {
                if (switcher.setPin(id, null)) platform.say(L.lockPinRemovedToast)
                back(id)
            }
        }
    }

    fun pinChosen(id: String, pin: String) {
        launch {
            if (switcher.setPin(id, pin)) platform.say(L.lockPinSetToast)
            back(id)
        }
    }

    fun saveName(id: String, name: String) {
        launch {
            switcher.rename(id, name)
            back(id)
        }
    }

    // ---- the work, on the app's scope ------------------------------------------------------

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a profile action failed", e)
                status = null
                notice = Notice(L.profilesSwitchFailed(name = ""), e.message.orEmpty().take(200))
            }
        }
    }

    /** One switch at a time from here too; the switcher itself keeps only the newest asked. */
    private fun work(line: String, block: suspend () -> Unit) {
        val before = running
        running = scope.launch {
            before?.join()
            status = line
            val watch = scope.launch {
                switcher.state.collect { state ->
                    status = when (state) {
                        is SwitchState.ClosingSlowly -> L.profilesClosingSlowly
                        else -> line
                    }
                }
            }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a profile switch failed", e)
                notice = Notice(L.profilesSwitchFailed(name = ""), "")
            } finally {
                watch.cancel()
                status = null
            }
        }
    }

    private fun switchNow(id: String, signInAgain: Boolean) {
        val name = nameOf(id)
        stage = Stage.Picker
        focusOn = id
        work(L.profilesSwitching(name = name)) {
            val left = switcher.profiles.value.active
            runCatching { platform.beforeSwitch() }.onFailure { Logger.w(TAG, "stopping before a switch failed", it) }
            when (val result = switcher.switchTo(id, signInAgain)) {
                SwitchResult.Switched, SwitchResult.Already -> {
                    // Leaving a profile that never finished signing in is Add account given up:
                    // it goes, folders and all, so a half made profile never lingers as a tile.
                    if (left != null && left.id != id && left.adding && !left.signedIn) switcher.remove(left.id)
                    enter()
                }
                SwitchResult.Superseded -> Unit
                is SwitchResult.Recoverable -> stage = Stage.Recoverable(id, result.state.reason)
                else -> notice = Notice(L.profilesSwitchFailed(name = name), "")
            }
        }
    }

    private fun addNow() {
        stage = Stage.Picker
        work(L.profilesSwitching(name = L.profilesAdd)) {
            runCatching { platform.beforeSwitch() }.onFailure { Logger.w(TAG, "stopping before an add failed", it) }
            when (val result = switcher.add()) {
                is AddResult.Added -> enter()
                AddResult.Full -> notice = Notice(L.profilesAdd, L.profilesLimit(count = com.tmplayer.data.AccountRules.MAX))
                is AddResult.NoSpace -> notice = Notice(
                    L.profilesNoSpaceTitle,
                    L.profilesNoSpaceBody(needed = L.messages.formatter.size(result.neededBytes), free = L.messages.formatter.size(result.freeBytes)),
                )
                else -> notice = Notice(L.profilesAdd, L.profilesAddFailed)
            }
        }
    }

    /** Sign out of [id] only: its data goes, then the picker (two left or more), the one left, or the sign in. */
    fun confirmSignOut(id: String, deleteDownloads: Boolean) {
        val name = nameOf(id)
        stage = Stage.Picker
        work(L.profilesSwitching(name = name)) {
            if (switcher.profiles.value.activeId != id) runCatching { platform.beforeSwitch() }
            when (val result = switcher.signOutProfile(id) { profile, last -> platform.wipe(profile, last, deleteDownloads) }) {
                is SignOutResult.SignedOut -> {
                    platform.say(if (result.partialWipe) L.profilesWipePartial else L.profilesSignedOut(name = name))
                    afterSignOut(result)
                }
                SignOutResult.Failed -> notice = Notice(L.profilesSignOut, L.profilesSignOutFailed(name = name))
                else -> notice = Notice(L.profilesSignOut, L.profilesSignOutFailed(name = name))
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

    /** Settings' sign out of the open profile, through the same switcher (its backup was asked before). */
    fun signOutOpen(deleteDownloads: Boolean, done: (SignOutResult) -> Unit = {}) {
        confirmSignOutWith(switcher.profiles.value.activeId, deleteDownloads, done)
    }

    private fun confirmSignOutWith(id: String, deleteDownloads: Boolean, done: (SignOutResult) -> Unit) {
        val name = nameOf(id)
        work(L.profilesSwitching(name = name)) {
            val result = switcher.signOutProfile(id) { profile, last -> platform.wipe(profile, last, deleteDownloads) }
            if (result is SignOutResult.SignedOut) {
                if (result.partialWipe) platform.say(L.profilesWipePartial)
                afterSignOut(result)
            } else {
                notice = Notice(L.profilesSignOut, L.profilesSignOutFailed(name = name))
            }
            done(result)
        }
    }

    /** Settings' Remove all accounts: every profile signed out, one empty profile on the sign in. */
    fun removeAll(deleteDownloads: Boolean, done: (SignOutResult) -> Unit = {}) {
        work(L.profilesRemoveAll) {
            runCatching { platform.beforeSwitch() }
            val result = switcher.removeAll { profile, last -> platform.wipe(profile, last, deleteDownloads) }
            when {
                result is SignOutResult.SignedOut && result.partialWipe -> platform.say(L.profilesWipePartial)
                result !is SignOutResult.SignedOut -> notice = Notice(L.profilesRemoveAll, L.profilesSignOutFailed(name = ""))
            }
            if (result is SignOutResult.SignedOut) enter()
            done(result)
        }
    }

    /** A broken profile's Sign in again: its folders made afresh, then its sign in. */
    fun signInAgain(id: String) = switchNow(id, signInAgain = true)

    /** A broken (or never signed in) sleeping profile's Remove, without reaching Telegram. */
    fun remove(id: String) {
        launch {
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
fun ProfilesGate(flow: ProfileFlow?, signInStep: Boolean, content: @Composable () -> Unit) {
    if (flow == null) {
        content()
        return
    }
    LaunchedEffect(flow) { flow.listen() }
    val list by flow.switcher.profiles.collectAsState()
    val several = ProfilePicker.atLaunch(list)
    val show = several && (flow.asked || !flow.entered)
    Box(Modifier.fillMaxSize()) {
        if (show) {
            ProfileFlowScreen(flow, list, signInStep)
        } else {
            key(list.activeId) { content() }
            // A profile on its sign in with others beside it: Back brings the picker.
            BackHandler(enabled = several && signInStep) { flow.ask() }
        }
        flow.notice?.let { notice ->
            ProfilePanel(
                title = notice.title,
                body = notice.body.ifBlank { null },
                actions = listOf(PanelAction(L.commonOk, flow::dismissNotice, primary = true)),
                onDismiss = flow::dismissNotice,
            )
        }
    }
}

/** The picker and whichever panel is over it. */
@Composable
fun ProfileFlowScreen(flow: ProfileFlow, list: AccountList, signInStep: Boolean) {
    val model = remember(list, signInStep) { flow.model(list, signInStep) }
    // From the menu, Back returns to the open profile; at launch it is left to the platform.
    if (flow.asked && flow.entered) BackHandler(enabled = flow.stage == ProfileFlow.Stage.Picker) { flow.close() }
    WhoIsWatching(
        model = model,
        onOpen = flow::open,
        onAdd = flow::add,
        onOptions = flow::options,
        status = flow.status,
        focusOn = flow.focusOn,
        focusAsk = flow.focusAsk,
    )
    val tile = { id: String -> model.tiles.firstOrNull { it.id == id } }
    when (val stage = flow.stage) {
        ProfileFlow.Stage.Picker -> Unit
        is ProfileFlow.Stage.Pin -> {
            val name = flow.nameOf(stage.id)
            val checker = remember(stage.id) { ProfilePinChecker(flow.switcher, stage.id) }
            PinPad(
                checker = checker,
                purpose = PinPurpose.Check,
                title = L.profilesPinFor(name = name),
                onDone = { flow.pinAccepted(stage.id, stage.why) },
                onCancel = { flow.back(stage.id) },
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
                body = L.profilesSwitchPlayBody,
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
